package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.TransitionRule;
import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.entity.ProcessDefinition;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.edoe.orchestrator.repository.ProcessDefinitionRepository;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.expression.EvaluationException;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransitionService {

    private static final Logger log = LoggerFactory.getLogger(TransitionService.class);
    private static final String COMPLETED_SENTINEL = "COMPLETED";
    private static final String PARALLEL_WAIT = "PARALLEL_WAIT";

    private final ProcessInstanceRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final ProcessDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    public TransitionService(ProcessInstanceRepository repository,
            OutboxEventRepository outboxRepository,
            ProcessDefinitionRepository definitionRepository,
            ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID startProcess(String definitionName, Map<String, Object> initialData) {
        ProcessDefinition definition = definitionRepository.findByName(definitionName)
                .orElseThrow(() -> new IllegalArgumentException("Unknown definition: " + definitionName));

        Map<String, Object> data = initialData != null ? initialData : Map.of();
        String contextJson = serializeContext(data);
        String initialStep = definition.getInitialStep();
        ProcessInstance instance = new ProcessInstance(definitionName, initialStep, contextJson, ProcessStatus.RUNNING);
        repository.saveAndFlush(instance);
        outboxRepository.save(new OutboxEvent(instance.getId().toString(), initialStep, contextJson));
        log.debug("Started process {} id={}", definitionName, instance.getId());
        return instance.getId();
    }

    @Transactional
    public void handleEvent(String processId, String eventType, Map<String, Object> outputData) {
        UUID uuid = UUID.fromString(processId);
        ProcessInstance instance = repository.findById(uuid)
                .orElseThrow(() -> new IllegalArgumentException("Unknown processId: " + processId));

        if (instance.getStatus() != ProcessStatus.RUNNING) {
            log.warn("Ignoring event {} for non-running process {} (status={})",
                    eventType, processId, instance.getStatus());
            return;
        }

        // Parallel wait: a branch from an active fork just completed
        if (PARALLEL_WAIT.equals(instance.getCurrentStep())) {
            if (eventType.endsWith("_FAILED")) {
                handleFailure(instance, outputData);
            } else {
                handleParallelBranchComplete(instance, eventType, outputData);
            }
            return;
        }

        String expectedEvent = instance.getCurrentStep() + "_FINISHED";
        String failedEvent = instance.getCurrentStep() + "_FAILED";

        if (eventType.equals(failedEvent)) {
            handleFailure(instance, outputData);
            return;
        }

        if (instance.getCompensating() != null && instance.getCompensating()) {
            if (eventType.equals(expectedEvent)) {
                Map<String, Object> mergedData = mergeContext(instance.getContextData(), outputData);
                executeNextCompensation(instance, mergedData);
            } else if (eventType.equals(failedEvent)) {
                instance.setStatus(ProcessStatus.FAILED);
                instance.setCompletedAt(LocalDateTime.now());
                repository.saveAndFlush(instance);
            } else {
                log.warn("Ignoring event {} for compensating process {} (step={})", eventType, processId,
                        instance.getCurrentStep());
            }
            return;
        }

        if (!eventType.equals(expectedEvent)) {
            log.warn("Ignoring stale/duplicate event {} for process {} (step={})",
                    eventType, processId, instance.getCurrentStep());
            return;
        }

        List<String> steps = deserializeStringList(instance.getCompletedSteps());
        steps.add(instance.getCurrentStep());
        instance.setCompletedSteps(serializeStringList(steps));

        ProcessDefinition definition = definitionRepository.findByName(instance.getDefinitionName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown definition: " + instance.getDefinitionName()));

        Map<String, List<TransitionRule>> transitions = deserializeTransitions(definition.getTransitionsJson());
        Map<String, Object> mergedData = mergeContext(instance.getContextData(), outputData);
        instance.setContextData(serializeContext(mergedData));

        List<TransitionRule> rules = transitions.get(eventType);
        TransitionRule matched = (rules != null) ? evaluateBranches(rules, mergedData) : null;

        if (matched != null && matched.isFork()) {
            dispatchFork(instance, matched.parallel(), matched.joinStep(), mergedData);
        } else if (matched != null && matched.isSuspendGate()) {
            suspendProcess(instance, matched.next(), mergedData);
        } else if (matched != null && matched.isDelay()) {
            scheduleProcess(instance, matched.next(), matched.delayMs(), mergedData);
        } else {
            String nextStep = (matched == null) ? COMPLETED_SENTINEL : matched.next();
            if (COMPLETED_SENTINEL.equals(nextStep)) {
                instance.setCurrentStep(COMPLETED_SENTINEL);
                instance.setStatus(ProcessStatus.COMPLETED);
                instance.setCompletedAt(LocalDateTime.now());
                repository.saveAndFlush(instance);
                log.debug("Process {} completed", processId);
            } else {
                instance.setCurrentStep(nextStep);
                instance.setStepStartedAt(LocalDateTime.now());
                repository.saveAndFlush(instance);
                outboxRepository.save(new OutboxEvent(processId, nextStep, serializeContext(mergedData)));
                log.debug("Process {} transitioned to step {}", processId, nextStep);
            }
        }
    }

    /**
     * Resumes a SUSPENDED process by injecting a named signal event.
     * The signal is evaluated exactly like a worker *_FINISHED event — transition
     * rules
     * keyed on {@code signalEvent} are looked up and the first matching branch
     * fires.
     * Signal data is merged into the process context before condition evaluation.
     */
    @Transactional
    public void handleSignal(String processId, String signalEvent, Map<String, Object> signalData) {
        UUID uuid = UUID.fromString(processId);
        ProcessInstance instance = repository.findById(uuid)
                .orElseThrow(() -> new IllegalArgumentException("Unknown processId: " + processId));

        if (instance.getStatus() != ProcessStatus.SUSPENDED) {
            throw new IllegalStateException(
                    "Cannot signal process " + processId + " in status: " + instance.getStatus());
        }

        ProcessDefinition definition = definitionRepository.findByName(instance.getDefinitionName())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown definition: " + instance.getDefinitionName()));

        List<String> steps = deserializeStringList(instance.getCompletedSteps());
        steps.add(instance.getCurrentStep());
        instance.setCompletedSteps(serializeStringList(steps));

        Map<String, List<TransitionRule>> transitions = deserializeTransitions(definition.getTransitionsJson());
        Map<String, Object> mergedData = mergeContext(instance.getContextData(), signalData);
        instance.setContextData(serializeContext(mergedData));

        List<TransitionRule> rules = transitions.get(signalEvent);
        TransitionRule matched = (rules != null) ? evaluateBranches(rules, mergedData) : null;

        if (matched != null && matched.isFork()) {
            instance.setStatus(ProcessStatus.RUNNING);
            dispatchFork(instance, matched.parallel(), matched.joinStep(), mergedData);
        } else if (matched != null && matched.isSuspendGate()) {
            suspendProcess(instance, matched.next(), mergedData);
        } else {
            String nextStep = (matched == null) ? COMPLETED_SENTINEL : matched.next();
            if (COMPLETED_SENTINEL.equals(nextStep)) {
                instance.setCurrentStep(COMPLETED_SENTINEL);
                instance.setStatus(ProcessStatus.COMPLETED);
                instance.setCompletedAt(LocalDateTime.now());
                repository.saveAndFlush(instance);
                log.debug("Process {} completed via signal {}", processId, signalEvent);
            } else {
                instance.setCurrentStep(nextStep);
                instance.setStatus(ProcessStatus.RUNNING);
                instance.setStepStartedAt(LocalDateTime.now());
                repository.saveAndFlush(instance);
                outboxRepository.save(new OutboxEvent(processId, nextStep, serializeContext(mergedData)));
                log.info("Process {} resumed from signal {}, transitioned to step {}",
                        processId, signalEvent, nextStep);
            }
        }
    }

    // -------------------------------------------------------------------------
    // Suspend
    // -------------------------------------------------------------------------

    /**
     * Parks the process at {@code suspendStep} with {@code status=SUSPENDED}.
     * No outbox command is written — the process waits for an external signal.
     */
    private void suspendProcess(ProcessInstance instance, String suspendStep, Map<String, Object> context) {
        instance.setCurrentStep(suspendStep);
        instance.setStatus(ProcessStatus.SUSPENDED);
        instance.setStepStartedAt(LocalDateTime.now());
        instance.setContextData(serializeContext(context));
        repository.saveAndFlush(instance);
        log.info("Process {} suspended at step {}", instance.getId(), suspendStep);
    }

    // -------------------------------------------------------------------------
    // Timer / Delay
    // -------------------------------------------------------------------------

    /**
     * Advances the process to {@code nextStep} but parks it with
     * {@code status=SCHEDULED} until {@code wakeAt} is reached.
     * No outbox command is written — {@link TimerService} will dispatch it.
     */
    private void scheduleProcess(ProcessInstance instance, String nextStep, long delayMs,
            Map<String, Object> context) {
        instance.setCurrentStep(nextStep);
        instance.setStatus(ProcessStatus.SCHEDULED);
        instance.setWakeAt(LocalDateTime.now().plusNanos(delayMs * 1_000_000L));
        instance.setStepStartedAt(LocalDateTime.now());
        instance.setContextData(serializeContext(context));
        repository.saveAndFlush(instance);
        log.info("Process {} scheduled at step {} — will wake at {}", instance.getId(), nextStep,
                instance.getWakeAt());
    }

    // -------------------------------------------------------------------------
    // Fork / Join
    // -------------------------------------------------------------------------

    /**
     * Sets the process into PARALLEL_WAIT state, persists fork metadata, and
     * enqueues one outbox command per parallel branch.
     */
    private void dispatchFork(ProcessInstance instance, List<String> parallelSteps,
            String joinStep, Map<String, Object> context) {
        instance.setCurrentStep(PARALLEL_WAIT);
        instance.setParallelPending(parallelSteps.size());
        instance.setJoinStep(joinStep);
        instance.setParallelCompleted("[]");
        instance.setStepStartedAt(LocalDateTime.now());
        repository.saveAndFlush(instance);

        String contextJson = serializeContext(context);
        for (String step : parallelSteps) {
            outboxRepository.save(new OutboxEvent(instance.getId().toString(), step, contextJson));
        }
        log.debug("Process {} forked into {} parallel branches: {}", instance.getId(), parallelSteps.size(),
                parallelSteps);
    }

    /**
     * Called when an event arrives while the process is in PARALLEL_WAIT.
     * Merges the branch output, decrements the pending count, and — when the
     * last branch reports — transitions to the join step.
     */
    private void handleParallelBranchComplete(ProcessInstance instance,
            String eventType,
            Map<String, Object> outputData) {
        // Idempotency: ignore if this branch already reported
        List<String> completed = deserializeStringList(instance.getParallelCompleted());
        if (completed.contains(eventType)) {
            log.warn("Duplicate parallel branch event {} for process {}, ignoring", eventType, instance.getId());
            return;
        }

        Map<String, Object> mergedData = mergeContext(instance.getContextData(), outputData);
        instance.setContextData(serializeContext(mergedData));

        completed.add(eventType);
        instance.setParallelCompleted(serializeStringList(completed));

        int remaining = instance.getParallelPending() - 1;
        instance.setParallelPending(remaining);

        if (remaining <= 0) {
            String joinStep = instance.getJoinStep();
            instance.setCurrentStep(joinStep);
            instance.setJoinStep(null);
            instance.setParallelPending(null);
            instance.setParallelCompleted(null);
            instance.setStepStartedAt(LocalDateTime.now());
            repository.saveAndFlush(instance);
            outboxRepository.save(new OutboxEvent(instance.getId().toString(), joinStep, serializeContext(mergedData)));
            log.debug("Process {} fork joined, transitioning to {}", instance.getId(), joinStep);
        } else {
            repository.saveAndFlush(instance);
            log.debug("Process {} parallel branch {} completed, {} remaining",
                    instance.getId(), eventType, remaining);
        }
    }

    // -------------------------------------------------------------------------
    // Compensation Execution
    // -------------------------------------------------------------------------

    private void handleFailure(ProcessInstance instance, Map<String, Object> outputData) {
        log.info("Process {} failed at step {}, starting compensation", instance.getId(), instance.getCurrentStep());
        instance.setCompensating(true);
        Map<String, Object> mergedData = mergeContext(instance.getContextData(), outputData);
        executeNextCompensation(instance, mergedData);
    }

    private void executeNextCompensation(ProcessInstance instance, Map<String, Object> contextData) {
        List<String> completed = deserializeStringList(instance.getCompletedSteps());
        ProcessDefinition definition = definitionRepository.findByName(instance.getDefinitionName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown definition: " + instance.getDefinitionName()));
        Map<String, String> compensations = deserializeStringMap(definition.getCompensationsJson());

        String contextJson = contextData != null ? serializeContext(contextData) : instance.getContextData();

        while (!completed.isEmpty()) {
            String step = completed.remove(completed.size() - 1);
            String compStep = compensations.get(step);
            if (compStep != null) {
                instance.setCompletedSteps(serializeStringList(completed));
                instance.setCurrentStep(compStep);
                instance.setStepStartedAt(LocalDateTime.now());
                instance.setContextData(contextJson);
                repository.saveAndFlush(instance);
                outboxRepository.save(new OutboxEvent(instance.getId().toString(), compStep, contextJson));
                log.debug("Process {} dispatching compensation step {} for {}", instance.getId(), compStep, step);
                return;
            }
        }

        instance.setCompletedSteps(serializeStringList(completed));
        instance.setContextData(contextJson);

        // No more steps to compensate. Or there were none mapped.
        // Terminal state is FAILED.
        instance.setStatus(ProcessStatus.FAILED);
        instance.setCompletedAt(LocalDateTime.now());
        repository.saveAndFlush(instance);
        log.info("Process {} compensation complete. Process is FAILED.", instance.getId());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /**
     * Evaluates branches top-to-bottom and returns the first matching
     * {@link TransitionRule}.
     * A null or blank condition is an unconditional default (always matches).
     * Each context key is available in SpEL as {@code #keyName}.
     * Returns {@code null} if no branch matches (treated as COMPLETED by the
     * caller).
     */
    private TransitionRule evaluateBranches(List<TransitionRule> rules, Map<String, Object> context) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        context.forEach(evalContext::setVariable);

        for (TransitionRule rule : rules) {
            if (rule.condition() == null || rule.condition().isBlank()) {
                return rule; // unconditional default
            }
            try {
                Boolean matches = spelParser.parseExpression(rule.condition()).getValue(evalContext, Boolean.class);
                if (Boolean.TRUE.equals(matches)) {
                    return rule;
                }
            } catch (EvaluationException e) {
                log.warn("Condition evaluation failed for expression '{}': {}", rule.condition(), e.getMessage());
            }
        }

        log.warn("No branch matched for rules {}; defaulting to COMPLETED", rules);
        return null;
    }

    private Map<String, List<TransitionRule>> deserializeTransitions(String transitionsJson) {
        try {
            return objectMapper.readValue(transitionsJson,
                    new TypeReference<Map<String, List<TransitionRule>>>() {
                    });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse transitions JSON", e);
        }
    }

    private Map<String, Object> mergeContext(String existingJson, Map<String, Object> newData) {
        Map<String, Object> merged = new HashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                merged.putAll(objectMapper.readValue(existingJson, new TypeReference<Map<String, Object>>() {
                }));
            } catch (JsonProcessingException e) {
                log.warn("Failed to parse existing context data, starting fresh: {}", e.getMessage());
            }
        }
        if (newData != null) {
            merged.putAll(newData);
        }
        return merged;
    }

    private String serializeContext(Map<String, Object> data) {
        try {
            return objectMapper.writeValueAsString(data);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize context data", e);
        }
    }

    private List<String> deserializeStringList(String json) {
        if (json == null || json.isBlank())
            return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<String>>() {
            });
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private String serializeStringList(List<String> list) {
        try {
            return objectMapper.writeValueAsString(list);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize string list", e);
        }
    }

    private Map<String, String> deserializeStringMap(String json) {
        if (json == null || json.isBlank())
            return Map.of();
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {
            });
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize string map", e);
        }
    }
}
