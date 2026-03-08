package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Service
public class TransitionService {

    private static final Logger log = LoggerFactory.getLogger(TransitionService.class);

    private static final String INITIAL_STEP = "STEP_1";
    private static final String COMPLETED_SENTINEL = "COMPLETED";

    private static final Map<String, String> TRANSITIONS = Map.of(
            "STEP_1_FINISHED", "STEP_2",
            "STEP_2_FINISHED", "COMPLETED"
    );

    private final ProcessInstanceRepository repository;
    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public TransitionService(ProcessInstanceRepository repository,
                             OutboxEventRepository outboxRepository,
                             ObjectMapper objectMapper) {
        this.repository = repository;
        this.outboxRepository = outboxRepository;
        this.objectMapper = objectMapper;
    }

    @Transactional
    public UUID startProcess(String definitionName, Map<String, Object> initialData) {
        Map<String, Object> data = initialData != null ? initialData : Map.of();
        String contextJson = serializeContext(data);
        ProcessInstance instance = new ProcessInstance(definitionName, INITIAL_STEP, contextJson, ProcessStatus.RUNNING);
        repository.saveAndFlush(instance);
        outboxRepository.save(new OutboxEvent(instance.getId().toString(), INITIAL_STEP, contextJson));
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

        Map<String, Object> mergedData = mergeContext(instance.getContextData(), outputData);
        instance.setContextData(serializeContext(mergedData));

        String nextStep = TRANSITIONS.getOrDefault(eventType, COMPLETED_SENTINEL);

        if (COMPLETED_SENTINEL.equals(nextStep)) {
            instance.setCurrentStep(COMPLETED_SENTINEL);
            instance.setStatus(ProcessStatus.COMPLETED);
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
