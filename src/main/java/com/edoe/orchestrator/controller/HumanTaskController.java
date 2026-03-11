package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.dto.CompleteTaskRequest;
import com.edoe.orchestrator.dto.HumanTaskResponse;
import com.edoe.orchestrator.entity.HumanTask;
import com.edoe.orchestrator.entity.HumanTaskStatus;
import com.edoe.orchestrator.service.HumanTaskService;
import com.edoe.orchestrator.service.ManagementService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/tasks")
@RequiredArgsConstructor
public class HumanTaskController {

    private final HumanTaskService humanTaskService;
    private final ManagementService managementService;

    /**
     * Lists all human tasks. Optional query params: {@code ?status=PENDING}, {@code ?assignee=john}.
     */
    @GetMapping
    public List<HumanTaskResponse> listTasks(
            @RequestParam(required = false) HumanTaskStatus status,
            @RequestParam(required = false) String assignee) {
        return humanTaskService.listTasks(status, assignee);
    }

    /**
     * Returns a single task by ID. Returns 404 if not found.
     */
    @GetMapping("/{id}")
    public HumanTaskResponse getTask(@PathVariable UUID id) {
        return humanTaskService.getTask(id);
    }

    /**
     * Completes a human task and signals the associated process to resume.
     * <ol>
     *   <li>Marks the task as COMPLETED and stores resultData.</li>
     *   <li>Signals the process via {@code managementService.signalProcess}.</li>
     * </ol>
     * Returns 409 if the task is not PENDING.
     */
    @PostMapping("/{id}/complete")
    public ResponseEntity<HumanTaskResponse> completeTask(
            @PathVariable UUID id,
            @RequestBody CompleteTaskRequest request) {
        Map<String, Object> resultData = request.resultData() != null ? request.resultData() : Map.of();
        HumanTask task = humanTaskService.completeTask(id, resultData);
        // Signal the process — this resumes the SUSPENDED process through the normal transition path
        managementService.signalProcess(task.getProcessInstanceId(), task.getSignalEvent(), resultData);
        return ResponseEntity.ok(humanTaskService.getTask(id));
    }

    /**
     * Cancels a task without signalling the process.
     * Use this when a task is no longer needed (e.g., the process was cancelled externally).
     */
    @PostMapping("/{id}/cancel")
    public HumanTaskResponse cancelTask(@PathVariable UUID id) {
        return humanTaskService.cancelTask(id);
    }
}
