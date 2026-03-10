package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.TransitionRule;
import com.edoe.orchestrator.entity.AuditEventType;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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

@Slf4j
@RequiredArgsConstructor
@Service
public class TransitionService {

    private static final String COMPLETED_SENTINEL = "COMPLETED";
    private static final String PARALLEL_WAIT = "PARALLEL_WAIT";
    private static final String MULTI_INSTANCE_WAIT = "MULTI_INSTANCE_WAIT";
    private static final String MI_SEPARATOR = "__MI__";

    private final ProcessInstanceRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final ProcessDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    @Transactional
    public UUID startProcess(String definitionName, Integer requestedVersion, Map<String, Object> initialData) {
        ProcessDefinition definition = (requestedVersion != null)
                ? definitionRepository.findByNameAndVersion(definitionName, requestedVersion)
                        .orElseThrow(() -> new IllegalArgumentException(
                                "Unknown definition: " + definitionName + " version " + requestedVersion))
                : definitionRepository.findTopByNameOrderByVersionDesc(definitionName)
                        .orElseThrow(() -> new IllegalArgumentException("Unknown definition: " + definitionName));

        Map<String, Object> data = initialData != null ? initialData : Map.of();
        String contextJson = serializeContext(data);
        String initialStep = definition.getInitialStep();
        ProcessInstance instance = new ProcessInstance(definitionName, definition.getVersion(), initialStep, contextJson, ProcessStatus.RUNNING);
        repository.saveAndFlush(instance);
        outboxRepository.save(new OutboxEvent(instance.getId().toString(), initialStep, contextJson));
        auditLogService.record(instance.getId(), AuditEventType.PROCESS_STARTED, initialStep,
                null, ProcessStatus.RUNNING, Map.of("definitionName", definitionName, "version", definition.getVersion(),
                        "contextSnapshot", contextJson));
        log.debug("Started process {} v{} id={}", definitionName, definition.getVersion(), instance.getId());
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

        // Multi-instance wait: an instance from a scatter-gather just completed
        if (MULTI_INSTANCE_WAIT.equals(instance.getCurrentStep())) {
            if (eventType.endsWith("_FAILED")) {
                handleMultiInstanceFailure(instance, outputData);
            } else {
                handleMultiInstanceBranchComplete(instance, eventType, outputData);
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

        ProcessDefinition definition = definitionRepository.findByNameAndVersion(instance.getDefinitionName(), instance.getDefinitionVersion())
                .orElseThrow(() -> new IllegalArgumentException("Unknown definition: " + instance.getDefinitionName()
                        + " v" + instance.getDefinitionVersion()));

        Map<String, List<TransitionRule>> transitions = deserializeTransitions(definition.getTransitionsJson());

        // Full merge is always computed — SpEL conditions must see the complete worker output
        Map<String, Object> fullMerge = mergeContext(instance.getContextData(), outputData);

        List<TransitionRule> rules = transitions.get(eventType);
        TransitionRule matched = (rules != null) ? evaluateBranches(rules, fullMerge) : null;

        // If the matched rule defines an outputMapping, persist only the mapped extractions;
        // otherwise persist the full merge (backward-compatible default behaviour)
        Map<String, Object> mergedData;
        if (matched != null && matched.hasOutputMapping()) {
            Map<String, Object> mappedOutput = applyOutputMapping(outputData, matched.outputMapping());
            mergedData = mergeContext(instance.getContextData(), mappedOutput);
        } else {
            mergedData = fullMerge;
        }
        instance.setContextData(serializeContext(mergedData));

        if (matched != null && matched.isFork()) {
            dispatchFork(instance, matched.parallel(), matched.joinStep(), mergedData);
        } else if (matched != null && matched.isMultiInstanceRule()) {
            dispatchMultiInstance(instance, matched.multiInstanceVariable(), matched.next(), matched.joinStep(), mergedData);
        } else if (matched != null && matched.isSuspendGate()) {
            suspendProcess(instance, matched.next(), mergedData);
        } else if (matched != null && matched.isDelay()) {
            scheduleProcess(instance, matched.next(), matched.delayMs(), mergedData);
        } else if (matched != null && matched.isCallActivityRule()) {
            startChildProcess(instance, matched.callActivity(), matched.next(), mergedData);
        } else {
            String nextStep = (matched == null) ? COMPLETED_SENTINEL : matched.next();
            if (COMPLETED_SENTINEL.equals(nextStep)) {
                completeProcess(instance);
            } else {
                String fromStep = instance.getCurrentStep();
                instance.setCurrentStep(nextStep);
                instance.setStepStartedAt(LocalDateTime.now());
                repository.saveAndFlush(instance);
                outboxRepository.save(new OutboxEvent(processId, nextStep, serializeContext(mergedData)));
                auditLogService.record(uuid, AuditEventType.STEP_TRANSITION, nextStep,
                        ProcessStatus.RUNNING, ProcessStatus.RUNNING,
                        Map.of("fromStep", fromStep, "toStep", nextStep,
                                "contextSnapshot", serializeContext(mergedData)));
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

        auditLogService.record(uuid, AuditEventType.SIGNAL_RECEIVED, instance.getCurrentStep(),
                null, null, Map.of("signal", signalEvent));

        ProcessDefinition definition = definitionRepository.findByNameAndVersion(instance.getDefinitionName(), instance.getDefinitionVersion())
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown definition: " + instance.getDefinitionName() + " v" + instance.getDefinitionVersion()));

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
        } else if (matched != null && matched.isCallActivityRule()) {
            instance.setStatus(ProcessStatus.RUNNING);
            startChildProcess(instance, matched.callActivity(), matched.next(), mergedData);
        } else {
            String nextStep = (matched == null) ? COMPLETED_SENTINEL : matched.next();
            if (COMPLETED_SENTINEL.equals(nextStep)) {
                instance.setStatus(ProcessStatus.COMPLETED);
                completeProcess(instance);
            } else {
                instance.setCurrentStep(nextStep);
                instance.setStatus(ProcessStatus.RUNNING);
                instance.setStepStartedAt(LocalDateTime.now());
                repository.saveAndFlush(instance);
                outboxRepository.save(new OutboxEvent(processId, nextStep, serializeContext(mergedData)));
                auditLogService.record(uuid, AuditEventType.PROCESS_RESUMED, nextStep,
                        ProcessStatus.SUSPENDED, ProcessStatus.RUNNING, Map.of("signal", signalEvent, "toStep", nextStep));
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
        ProcessStatus fromStatus = instance.getStatus();
        instance.setCurrentStep(suspendStep);
        instance.setStatus(ProcessStatus.SUSPENDED);
        instance.setStepStartedAt(LocalDateTime.now());
        instance.setContextData(serializeContext(context));
        repository.saveAndFlush(instance);
        auditLogService.record(instance.getId(), AuditEventType.PROCESS_SUSPENDED, suspendStep,
                fromStatus, ProcessStatus.SUSPENDED, null);
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
        auditLogService.record(instance.getId(), AuditEventType.PROCESS_SCHEDULED, nextStep,
                ProcessStatus.RUNNING, ProcessStatus.SCHEDULED,
                Map.of("delayMs", delayMs, "wakeAt", instance.getWakeAt().toString()));
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
        auditLogService.record(instance.getId(), AuditEventType.FORK_DISPATCHED, PARALLEL_WAIT,
                ProcessStatus.RUNNING, ProcessStatus.RUNNING,
                Map.of("branches", parallelSteps.size(), "joinStep", joinStep));
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
            auditLogService.record(instance.getId(), AuditEventType.FORK_JOINED, joinStep,
                    ProcessStatus.RUNNING, ProcessStatus.RUNNING, null);
            log.debug("Process {} fork joined, transitioning to {}", instance.getId(), joinStep);
        } else {
            repository.saveAndFlush(instance);
            auditLogService.record(instance.getId(), AuditEventType.FORK_BRANCH_COMPLETED, eventType,
                    ProcessStatus.RUNNING, ProcessStatus.RUNNING, Map.of("remaining", remaining));
            log.debug("Process {} parallel branch {} completed, {} remaining",
                    instance.getId(), eventType, remaining);
        }
    }

    // -------------------------------------------------------------------------
    // Multi-Instance / Scatter-Gather
    // -------------------------------------------------------------------------

    /**
     * Reads the named collection from the context, dispatches one indexed command
     * per element ({@code miStep__MI__0}, {@code miStep__MI__1}, …), and parks the
     * process in {@code MULTI_INSTANCE_WAIT}. If the collection is empty or absent,
     * the process skips directly to {@code joinStep} with an empty results list.
     */
    private void dispatchMultiInstance(ProcessInstance instance, String variableName,
            String miStep, String joinStep, Map<String, Object> context) {
        Object raw = context.get(variableName);
        List<?> items = null;
        if (raw instanceof List<?> list) {
            items = list;
        } else if (raw != null) {
            log.warn("Process {} multiInstanceVariable '{}' is not a List (type={}), treating as empty",
                    instance.getId(), variableName, raw.getClass().getSimpleName());
        }

        if (items == null || items.isEmpty()) {
            // Zero-iteration: skip directly to joinStep
            context.put("multiInstanceResults", List.of());
            instance.setCurrentStep(joinStep);
            instance.setStepStartedAt(LocalDateTime.now());
            instance.setContextData(serializeContext(context));
            repository.saveAndFlush(instance);
            outboxRepository.save(new OutboxEvent(instance.getId().toString(), joinStep, serializeContext(context)));
            log.info("Process {} multi-instance '{}' is empty, skipping directly to {}",
                    instance.getId(), variableName, joinStep);
            return;
        }

        instance.setCurrentStep(MULTI_INSTANCE_WAIT);
        instance.setMiStep(miStep);
        instance.setParallelPending(items.size());
        instance.setJoinStep(joinStep);
        instance.setParallelCompleted("[]");
        instance.setMiResults("[]");
        instance.setStepStartedAt(LocalDateTime.now());
        instance.setContextData(serializeContext(context));
        repository.saveAndFlush(instance);

        for (int i = 0; i < items.size(); i++) {
            String commandType = miStep + MI_SEPARATOR + i;
            Map<String, Object> itemContext = new HashMap<>(context);
            itemContext.put("__miItem", items.get(i));
            itemContext.put("__miIndex", i);
            outboxRepository.save(new OutboxEvent(instance.getId().toString(), commandType, serializeContext(itemContext)));
        }
        auditLogService.record(instance.getId(), AuditEventType.MULTI_INSTANCE_DISPATCHED, miStep,
                ProcessStatus.RUNNING, ProcessStatus.RUNNING,
                Map.of("count", items.size(), "joinStep", joinStep));
        log.info("Process {} multi-instance scatter: {} × {} instances (joinStep={})",
                instance.getId(), items.size(), miStep, joinStep);
    }

    /**
     * Called when an indexed MI event ({@code STEP__MI__N_FINISHED}) arrives while
     * the process is in MULTI_INSTANCE_WAIT. Appends the output, decrements the
     * pending count, and — when the last instance reports — gathers all results and
     * transitions to the join step.
     */
    private void handleMultiInstanceBranchComplete(ProcessInstance instance,
            String eventType, Map<String, Object> outputData) {
        // Idempotency: ignore if this instance already reported
        List<String> completed = deserializeStringList(instance.getParallelCompleted());
        if (completed.contains(eventType)) {
            log.warn("Duplicate MI event {} for process {}, ignoring", eventType, instance.getId());
            return;
        }

        // Append per-instance output
        List<Map<String, Object>> results = deserializeResultsList(instance.getMiResults());
        results.add(outputData != null ? outputData : Map.of());

        // Merge into shared context (same as parallel branches)
        Map<String, Object> mergedData = mergeContext(instance.getContextData(), outputData);
        instance.setContextData(serializeContext(mergedData));
        instance.setMiResults(serializeMiResults(results));

        completed.add(eventType);
        instance.setParallelCompleted(serializeStringList(completed));

        int remaining = instance.getParallelPending() - 1;
        instance.setParallelPending(remaining);

        if (remaining <= 0) {
            // Gather: put collected results into context under "multiInstanceResults"
            mergedData.put("multiInstanceResults", results);
            String joinStep = instance.getJoinStep();
            instance.setCurrentStep(joinStep);
            instance.setJoinStep(null);
            instance.setParallelPending(null);
            instance.setParallelCompleted(null);
            instance.setMiStep(null);
            instance.setMiResults(null);
            instance.setStepStartedAt(LocalDateTime.now());
            instance.setContextData(serializeContext(mergedData));
            repository.saveAndFlush(instance);
            outboxRepository.save(new OutboxEvent(instance.getId().toString(), joinStep, serializeContext(mergedData)));
            auditLogService.record(instance.getId(), AuditEventType.MULTI_INSTANCE_JOINED, joinStep,
                    ProcessStatus.RUNNING, ProcessStatus.RUNNING, null);
            log.info("Process {} multi-instance gather complete, transitioning to {}", instance.getId(), joinStep);
        } else {
            repository.saveAndFlush(instance);
            auditLogService.record(instance.getId(), AuditEventType.MULTI_INSTANCE_BRANCH_COMPLETED, eventType,
                    ProcessStatus.RUNNING, ProcessStatus.RUNNING, Map.of("remaining", remaining));
            log.debug("Process {} MI branch {} completed, {} remaining",
                    instance.getId(), eventType, remaining);
        }
    }

    /**
     * Handles a {@code _FAILED} event during MULTI_INSTANCE_WAIT.
     * Clears all MI state and delegates to the standard failure/compensation path.
     */
    private void handleMultiInstanceFailure(ProcessInstance instance, Map<String, Object> outputData) {
        log.info("Process {} multi-instance branch failed, failing process", instance.getId());
        instance.setMiStep(null);
        instance.setMiResults(null);
        instance.setJoinStep(null);
        instance.setParallelPending(null);
        instance.setParallelCompleted(null);
        handleFailure(instance, outputData);
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
        ProcessDefinition definition = definitionRepository.findByNameAndVersion(instance.getDefinitionName(), instance.getDefinitionVersion())
                .orElseThrow(() -> new IllegalArgumentException("Unknown definition: " + instance.getDefinitionName()
                        + " v" + instance.getDefinitionVersion()));
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
                auditLogService.record(instance.getId(), AuditEventType.COMPENSATION_STEP, compStep,
                        null, null, Map.of("compensatingFor", step));
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
        auditLogService.record(instance.getId(), AuditEventType.PROCESS_FAILED, instance.getCurrentStep(),
                ProcessStatus.RUNNING, ProcessStatus.FAILED, null);
        log.info("Process {} compensation complete. Process is FAILED.", instance.getId());

        // Propagate failure to parent if this is a child process
        if (instance.getParentProcessId() != null) {
            failParentProcess(instance);
        }
    }

    // -------------------------------------------------------------------------
    // Sub-Process / Call Activity
    // -------------------------------------------------------------------------

    /**
     * Completes a process and propagates to the parent if this is a child.
     */
    private void completeProcess(ProcessInstance instance) {
        ProcessStatus fromStatus = instance.getStatus();
        instance.setCurrentStep(COMPLETED_SENTINEL);
        instance.setStatus(ProcessStatus.COMPLETED);
        instance.setCompletedAt(LocalDateTime.now());
        repository.saveAndFlush(instance);
        auditLogService.record(instance.getId(), AuditEventType.PROCESS_COMPLETED, COMPLETED_SENTINEL,
                fromStatus, ProcessStatus.COMPLETED, null);
        log.debug("Process {} completed", instance.getId());

        if (instance.getParentProcessId() != null) {
            resumeParentProcess(instance);
        }
    }

    /**
     * Spawns a child process from the named definition and parks the parent
     * at {@code WAITING_FOR_CHILD}.
     */
    private void startChildProcess(ProcessInstance parent, String childDefinitionName,
            String nextAfterChild, Map<String, Object> context) {
        ProcessDefinition childDef = definitionRepository.findTopByNameOrderByVersionDesc(childDefinitionName)
                .orElseThrow(() -> new IllegalArgumentException(
                        "Unknown child definition: " + childDefinitionName));

        // Park parent
        parent.setStatus(ProcessStatus.WAITING_FOR_CHILD);
        parent.setStepStartedAt(LocalDateTime.now());
        parent.setContextData(serializeContext(context));
        repository.saveAndFlush(parent);

        // Create child instance with parent linkage, snapshotting the child definition version
        String contextJson = serializeContext(context);
        ProcessInstance child = new ProcessInstance(
                childDefinitionName, childDef.getVersion(), childDef.getInitialStep(), contextJson, ProcessStatus.RUNNING);
        child.setParentProcessId(parent.getId());
        child.setParentNextStep(nextAfterChild);
        repository.saveAndFlush(child);

        // Dispatch child's initial step
        outboxRepository.save(new OutboxEvent(
                child.getId().toString(), childDef.getInitialStep(), contextJson));
        auditLogService.record(child.getId(), AuditEventType.CHILD_PROCESS_STARTED, childDef.getInitialStep(),
                null, ProcessStatus.RUNNING,
                Map.of("parentId", parent.getId().toString(), "definition", childDefinitionName));
        log.info("Process {} spawned child {} (definition={}), waiting for child",
                parent.getId(), child.getId(), childDefinitionName);
    }

    /**
     * Resumes the parent process after a child completes successfully.
     * Merges the child's final context back into the parent.
     */
    private void resumeParentProcess(ProcessInstance child) {
        UUID parentId = child.getParentProcessId();
        ProcessInstance parent = repository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent process not found: " + parentId));

        if (parent.getStatus() != ProcessStatus.WAITING_FOR_CHILD) {
            log.warn("Parent {} is not WAITING_FOR_CHILD (status={}), skipping resume",
                    parentId, parent.getStatus());
            return;
        }

        // Merge child's final context into parent
        Map<String, Object> childContext = mergeContext(child.getContextData(), null);
        Map<String, Object> parentContext = mergeContext(parent.getContextData(), childContext);
        parent.setContextData(serializeContext(parentContext));

        String nextStep = child.getParentNextStep();
        if (nextStep == null || COMPLETED_SENTINEL.equals(nextStep)) {
            completeProcess(parent);
        } else {
            parent.setCurrentStep(nextStep);
            parent.setStatus(ProcessStatus.RUNNING);
            parent.setStepStartedAt(LocalDateTime.now());

            // Track call-activity step as completed for saga purposes
            List<String> steps = deserializeStringList(parent.getCompletedSteps());
            steps.add(child.getDefinitionName() + "_CALL_ACTIVITY");
            parent.setCompletedSteps(serializeStringList(steps));

            repository.saveAndFlush(parent);
            outboxRepository.save(new OutboxEvent(
                    parentId.toString(), nextStep, serializeContext(parentContext)));
            auditLogService.record(parentId, AuditEventType.PARENT_RESUMED, nextStep,
                    ProcessStatus.WAITING_FOR_CHILD, ProcessStatus.RUNNING,
                    Map.of("childId", child.getId().toString()));
            log.info("Parent {} resumed at step {} after child {} completed",
                    parentId, nextStep, child.getId());
        }
    }

    /**
     * Propagates failure from a child process to its parent.
     * Triggers the parent's own compensation chain if configured.
     */
    private void failParentProcess(ProcessInstance child) {
        UUID parentId = child.getParentProcessId();
        ProcessInstance parent = repository.findById(parentId)
                .orElseThrow(() -> new IllegalArgumentException("Parent process not found: " + parentId));

        if (parent.getStatus() != ProcessStatus.WAITING_FOR_CHILD) {
            log.warn("Parent {} not WAITING_FOR_CHILD, skipping failure propagation", parentId);
            return;
        }

        // Merge child error context into parent
        Map<String, Object> childContext = mergeContext(child.getContextData(), null);
        Map<String, Object> parentContext = mergeContext(parent.getContextData(), childContext);
        parent.setContextData(serializeContext(parentContext));
        parent.setStatus(ProcessStatus.RUNNING); // temporarily set RUNNING so handleFailure can process it

        auditLogService.record(parentId, AuditEventType.PARENT_FAILED, null,
                ProcessStatus.WAITING_FOR_CHILD, ProcessStatus.RUNNING,
                Map.of("childId", child.getId().toString()));

        handleFailure(parent, Map.of("childProcessFailed", true,
                "childProcessId", child.getId().toString()));
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

    /**
     * Applies JSONPath expressions to the raw worker output and returns a new map
     * containing only the mapped extractions. Keys not present in {@code mapping}
     * are silently discarded. Missing JSONPath paths are logged at DEBUG and skipped.
     */
    private Map<String, Object> applyOutputMapping(Map<String, Object> output,
            Map<String, String> mapping) {
        Map<String, Object> result = new HashMap<>();
        if (output == null || output.isEmpty()) {
            return result;
        }
        String outputJson;
        try {
            outputJson = objectMapper.writeValueAsString(output);
        } catch (JsonProcessingException e) {
            log.warn("applyOutputMapping: failed to serialize worker output: {}", e.getMessage());
            return result;
        }
        for (Map.Entry<String, String> entry : mapping.entrySet()) {
            try {
                Object value = com.jayway.jsonpath.JsonPath.read(outputJson, entry.getValue());
                result.put(entry.getKey(), value);
            } catch (com.jayway.jsonpath.PathNotFoundException e) {
                log.debug("applyOutputMapping: path '{}' not found for key '{}', skipping",
                        entry.getValue(), entry.getKey());
            } catch (Exception e) {
                log.warn("applyOutputMapping: path '{}' evaluation failed for key '{}': {}",
                        entry.getValue(), entry.getKey(), e.getMessage());
            }
        }
        return result;
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

    private List<Map<String, Object>> deserializeResultsList(String json) {
        if (json == null || json.isBlank())
            return new ArrayList<>();
        try {
            return objectMapper.readValue(json, new TypeReference<List<Map<String, Object>>>() {
            });
        } catch (JsonProcessingException e) {
            return new ArrayList<>();
        }
    }

    private String serializeMiResults(List<Map<String, Object>> results) {
        try {
            return objectMapper.writeValueAsString(results);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize MI results", e);
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
