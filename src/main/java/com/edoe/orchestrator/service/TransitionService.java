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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
public class TransitionService {

    private static final Logger log = LoggerFactory.getLogger(TransitionService.class);
    private static final String COMPLETED_SENTINEL = "COMPLETED";

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

        String expectedEvent = instance.getCurrentStep() + "_FINISHED";
        if (!eventType.equals(expectedEvent) || instance.getStatus() != ProcessStatus.RUNNING) {
            log.warn("Ignoring stale/duplicate event {} for process {} (step={}, status={})",
                    eventType, processId, instance.getCurrentStep(), instance.getStatus());
            return;
        }

        ProcessDefinition definition = definitionRepository.findByName(instance.getDefinitionName())
                .orElseThrow(() -> new IllegalArgumentException("Unknown definition: " + instance.getDefinitionName()));

        Map<String, List<TransitionRule>> transitions = deserializeTransitions(definition.getTransitionsJson());
        Map<String, Object> mergedData = mergeContext(instance.getContextData(), outputData);
        instance.setContextData(serializeContext(mergedData));

        List<TransitionRule> rules = transitions.get(eventType);
        String nextStep = (rules != null) ? evaluateBranches(rules, mergedData) : COMPLETED_SENTINEL;

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

    /**
     * Evaluates branches top-to-bottom and returns the {@code next} of the first matching rule.
     * A null or blank condition is an unconditional default (always matches).
     * Each context key is available in SpEL as {@code #keyName}.
     * If no branch matches, returns {@link #COMPLETED_SENTINEL}.
     */
    private String evaluateBranches(List<TransitionRule> rules, Map<String, Object> context) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        context.forEach(evalContext::setVariable);

        for (TransitionRule rule : rules) {
            if (rule.condition() == null || rule.condition().isBlank()) {
                return rule.next(); // unconditional default
            }
            try {
                Boolean matches = spelParser.parseExpression(rule.condition()).getValue(evalContext, Boolean.class);
                if (Boolean.TRUE.equals(matches)) {
                    return rule.next();
                }
            } catch (EvaluationException e) {
                log.warn("Condition evaluation failed for expression '{}': {}", rule.condition(), e.getMessage());
            }
        }

        log.warn("No branch matched for rules {}; defaulting to COMPLETED", rules);
        return COMPLETED_SENTINEL;
    }

    private Map<String, List<TransitionRule>> deserializeTransitions(String transitionsJson) {
        try {
            return objectMapper.readValue(transitionsJson,
                    new TypeReference<Map<String, List<TransitionRule>>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to parse transitions JSON", e);
        }
    }

    private Map<String, Object> mergeContext(String existingJson, Map<String, Object> newData) {
        Map<String, Object> merged = new HashMap<>();
        if (existingJson != null && !existingJson.isBlank()) {
            try {
                merged.putAll(objectMapper.readValue(existingJson, new TypeReference<Map<String, Object>>() {}));
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
}
