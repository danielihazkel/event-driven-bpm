package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.*;
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
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Service
public class ManagementService {

    private final ProcessDefinitionRepository definitionRepository;
    private final ProcessInstanceRepository instanceRepository;
    private final OutboxEventRepository outboxRepository;
    private final TransitionService transitionService;
    private final ObjectMapper objectMapper;

    public ManagementService(ProcessDefinitionRepository definitionRepository,
                             ProcessInstanceRepository instanceRepository,
                             OutboxEventRepository outboxRepository,
                             TransitionService transitionService,
                             ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.instanceRepository = instanceRepository;
        this.outboxRepository = outboxRepository;
        this.transitionService = transitionService;
        this.objectMapper = objectMapper;
    }

    // --- Process Definitions ---

    public List<ProcessDefinitionResponse> listDefinitions() {
        return definitionRepository.findAll().stream()
                .map(this::toDefinitionResponse)
                .toList();
    }

    @Transactional
    public ProcessDefinitionResponse createDefinition(ProcessDefinitionRequest request) {
        if (definitionRepository.existsByName(request.name())) {
            throw new IllegalStateException("Definition already exists: " + request.name());
        }
        ProcessDefinition def = new ProcessDefinition(
                request.name(),
                request.initialStep(),
                serializeTransitions(request.transitions())
        );
        return toDefinitionResponse(definitionRepository.save(def));
    }

    public ProcessDefinitionResponse getDefinition(String name) {
        return definitionRepository.findByName(name)
                .map(this::toDefinitionResponse)
                .orElseThrow(() -> new NoSuchElementException("Definition not found: " + name));
    }

    @Transactional
    public ProcessDefinitionResponse updateDefinition(String name, ProcessDefinitionRequest request) {
        ProcessDefinition def = definitionRepository.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Definition not found: " + name));
        def.setName(request.name());
        def.setInitialStep(request.initialStep());
        def.setTransitionsJson(serializeTransitions(request.transitions()));
        def.setUpdatedAt(LocalDateTime.now());
        return toDefinitionResponse(definitionRepository.save(def));
    }

    @Transactional
    public void deleteDefinition(String name) {
        ProcessDefinition def = definitionRepository.findByName(name)
                .orElseThrow(() -> new NoSuchElementException("Definition not found: " + name));
        boolean hasActive = instanceRepository.existsByDefinitionNameAndStatusIn(
                name, Set.of(ProcessStatus.RUNNING, ProcessStatus.STALLED));
        if (hasActive) {
            throw new IllegalStateException("Cannot delete definition with active processes: " + name);
        }
        definitionRepository.delete(def);
    }

    // --- Process Instances ---

    public Page<ProcessInstanceResponse> listProcesses(ProcessStatus status, String definitionName, Pageable pageable) {
        Page<ProcessInstance> page;
        if (status != null && definitionName != null) {
            page = instanceRepository.findByStatusAndDefinitionName(status, definitionName, pageable);
        } else if (status != null) {
            page = instanceRepository.findByStatus(status, pageable);
        } else if (definitionName != null) {
            page = instanceRepository.findByDefinitionName(definitionName, pageable);
        } else {
            page = instanceRepository.findAll(pageable);
        }
        return page.map(this::toInstanceResponse);
    }

    @Transactional
    public ProcessInstanceResponse cancelProcess(UUID id) {
        ProcessInstance instance = instanceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Process not found: " + id));
        if (instance.getStatus() != ProcessStatus.RUNNING && instance.getStatus() != ProcessStatus.STALLED) {
            throw new IllegalStateException("Cannot cancel process in status: " + instance.getStatus());
        }
        instance.setStatus(ProcessStatus.CANCELLED);
        instance.setCompletedAt(LocalDateTime.now());
        return toInstanceResponse(instanceRepository.saveAndFlush(instance));
    }

    @Transactional
    public ProcessInstanceResponse retryProcess(UUID id) {
        ProcessInstance instance = instanceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Process not found: " + id));
        if (instance.getStatus() != ProcessStatus.FAILED && instance.getStatus() != ProcessStatus.STALLED) {
            throw new IllegalStateException("Cannot retry process in status: " + instance.getStatus());
        }
        instance.setStatus(ProcessStatus.RUNNING);
        instance.setStepStartedAt(LocalDateTime.now());
        instanceRepository.saveAndFlush(instance);
        outboxRepository.save(new OutboxEvent(id.toString(), instance.getCurrentStep(), instance.getContextData()));
        return toInstanceResponse(instance);
    }

    @Transactional
    public ProcessInstanceResponse advanceProcess(UUID id) {
        ProcessInstance instance = instanceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Process not found: " + id));
        if (instance.getStatus() != ProcessStatus.RUNNING) {
            throw new IllegalStateException("Cannot advance process in status: " + instance.getStatus());
        }
        String syntheticEvent = instance.getCurrentStep() + "_FINISHED";
        transitionService.handleEvent(id.toString(), syntheticEvent, Map.of());
        // Re-fetch to get the updated state after handleEvent
        instance = instanceRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Process not found after advance: " + id));
        return toInstanceResponse(instance);
    }

    // --- Metrics ---

    public MetricsSummaryResponse getMetricsSummary() {
        long running = instanceRepository.countByStatus(ProcessStatus.RUNNING);
        long completed = instanceRepository.countByStatus(ProcessStatus.COMPLETED);
        long failed = instanceRepository.countByStatus(ProcessStatus.FAILED);
        long stalled = instanceRepository.countByStatus(ProcessStatus.STALLED);
        long cancelled = instanceRepository.countByStatus(ProcessStatus.CANCELLED);
        long total = running + completed + failed + stalled + cancelled;
        long denominator = completed + failed + cancelled;
        double successRate = denominator > 0 ? (double) completed / denominator : 0.0;
        return new MetricsSummaryResponse(total, running, completed, failed, stalled, cancelled, successRate);
    }

    // --- Mapping helpers ---

    private ProcessDefinitionResponse toDefinitionResponse(ProcessDefinition def) {
        Map<String, String> transitions = deserializeTransitions(def.getTransitionsJson());
        return new ProcessDefinitionResponse(def.getId(), def.getName(), def.getInitialStep(),
                transitions, def.getCreatedAt(), def.getUpdatedAt());
    }

    private ProcessInstanceResponse toInstanceResponse(ProcessInstance inst) {
        return new ProcessInstanceResponse(inst.getId(), inst.getDefinitionName(), inst.getCurrentStep(),
                inst.getStatus(), inst.getCreatedAt(), inst.getStepStartedAt(),
                inst.getCompletedAt(), inst.getContextData());
    }

    private String serializeTransitions(Map<String, String> transitions) {
        try {
            return objectMapper.writeValueAsString(transitions);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize transitions", e);
        }
    }

    private Map<String, String> deserializeTransitions(String json) {
        try {
            return objectMapper.readValue(json, new TypeReference<Map<String, String>>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize transitions", e);
        }
    }
}
