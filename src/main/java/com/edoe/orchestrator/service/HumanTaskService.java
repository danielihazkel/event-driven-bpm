package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.HumanTaskDefinition;
import com.edoe.orchestrator.dto.HumanTaskResponse;
import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.HumanTask;
import com.edoe.orchestrator.entity.HumanTaskStatus;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.repository.HumanTaskRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class HumanTaskService {

    private final HumanTaskRepository humanTaskRepository;
    private final AuditLogService auditLogService;
    private final ObjectMapper objectMapper;

    /**
     * Creates a new PENDING human task linked to the given process instance.
     * Called by {@link TransitionService} when a humanTask rule is matched.
     */
    @Transactional
    public HumanTask createTask(ProcessInstance instance, HumanTaskDefinition definition,
                                String resolvedAssignee) {
        String formSchemaJson = serializeMap(definition.formSchema());
        HumanTask task = new HumanTask(
                instance.getId(),
                instance.getDefinitionName(),
                definition.taskName(),
                definition.signalEvent(),
                formSchemaJson,
                resolvedAssignee);
        humanTaskRepository.save(task);
        auditLogService.record(instance.getId(), AuditEventType.HUMAN_TASK_CREATED,
                instance.getCurrentStep(), null, null,
                Map.of("taskId", task.getId().toString(), "taskName", definition.taskName()));
        log.info("Created human task {} for process {}", task.getId(), instance.getId());
        return task;
    }

    /**
     * Lists tasks, optionally filtered by status and/or assignee.
     */
    public List<HumanTaskResponse> listTasks(HumanTaskStatus status, String assignee) {
        List<HumanTask> tasks;
        if (status != null && assignee != null) {
            tasks = humanTaskRepository.findByAssigneeAndStatus(assignee, status);
        } else if (status != null) {
            tasks = humanTaskRepository.findByStatus(status);
        } else if (assignee != null) {
            tasks = humanTaskRepository.findByAssigneeAndStatus(assignee, HumanTaskStatus.PENDING);
        } else {
            tasks = humanTaskRepository.findAll();
        }
        return tasks.stream().map(this::toResponse).toList();
    }

    /**
     * Returns a single task by ID. Throws {@link NoSuchElementException} → 404 if not found.
     */
    public HumanTaskResponse getTask(UUID id) {
        return humanTaskRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NoSuchElementException("Human task not found: " + id));
    }

    /**
     * Marks a task as COMPLETED and stores the submitted result data.
     * Throws {@link IllegalStateException} → 409 if the task is not PENDING.
     * The caller (controller) is responsible for signalling the process.
     */
    @Transactional
    public HumanTask completeTask(UUID id, Map<String, Object> resultData) {
        HumanTask task = humanTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Human task not found: " + id));
        if (task.getStatus() != HumanTaskStatus.PENDING) {
            throw new IllegalStateException("Cannot complete task in status: " + task.getStatus());
        }
        task.setStatus(HumanTaskStatus.COMPLETED);
        task.setCompletedAt(LocalDateTime.now());
        task.setResultData(serializeMap(resultData));
        humanTaskRepository.save(task);
        auditLogService.record(task.getProcessInstanceId(), AuditEventType.HUMAN_TASK_COMPLETED,
                null, null, null,
                Map.of("taskId", id.toString(), "taskName", task.getTaskName()));
        log.info("Completed human task {} for process {}", id, task.getProcessInstanceId());
        return task;
    }

    /**
     * Marks a task as CANCELLED (does NOT signal the associated process).
     */
    @Transactional
    public HumanTaskResponse cancelTask(UUID id) {
        HumanTask task = humanTaskRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Human task not found: " + id));
        task.setStatus(HumanTaskStatus.CANCELLED);
        task.setCompletedAt(LocalDateTime.now());
        humanTaskRepository.save(task);
        auditLogService.record(task.getProcessInstanceId(), AuditEventType.HUMAN_TASK_CANCELLED,
                null, null, null,
                Map.of("taskId", id.toString(), "taskName", task.getTaskName()));
        log.info("Cancelled human task {} for process {}", id, task.getProcessInstanceId());
        return toResponse(task);
    }

    /**
     * Cancels all PENDING tasks for the given process instance.
     * Called by {@link ManagementService} when a process is cancelled.
     */
    @Transactional
    public void cancelTasksForProcess(UUID processInstanceId) {
        List<HumanTask> pending = humanTaskRepository.findByProcessInstanceId(processInstanceId)
                .stream()
                .filter(t -> t.getStatus() == HumanTaskStatus.PENDING)
                .toList();
        LocalDateTime now = LocalDateTime.now();
        for (HumanTask task : pending) {
            task.setStatus(HumanTaskStatus.CANCELLED);
            task.setCompletedAt(now);
            humanTaskRepository.save(task);
            auditLogService.record(processInstanceId, AuditEventType.HUMAN_TASK_CANCELLED,
                    null, null, null,
                    Map.of("taskId", task.getId().toString(), "taskName", task.getTaskName(),
                            "cancelledByProcess", processInstanceId.toString()));
        }
        if (!pending.isEmpty()) {
            log.info("Cancelled {} pending human task(s) for process {}", pending.size(), processInstanceId);
        }
    }

    // -------------------------------------------------------------------------
    // Mapping helpers
    // -------------------------------------------------------------------------

    private HumanTaskResponse toResponse(HumanTask task) {
        return new HumanTaskResponse(
                task.getId(),
                task.getProcessInstanceId(),
                task.getProcessDefinitionName(),
                task.getTaskName(),
                task.getSignalEvent(),
                deserializeMap(task.getFormSchema()),
                task.getAssignee(),
                task.getStatus(),
                task.getCreatedAt(),
                task.getCompletedAt(),
                deserializeMap(task.getResultData()));
    }

    private String serializeMap(Map<String, Object> map) {
        if (map == null) return null;
        try {
            return objectMapper.writeValueAsString(map);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize map", e);
        }
    }

    private Map<String, Object> deserializeMap(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize map", e);
        }
    }
}
