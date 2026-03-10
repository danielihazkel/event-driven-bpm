package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.dto.*;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.service.ManagementService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RequiredArgsConstructor
@RestController
@RequestMapping("/api")
@Tag(name = "Process Management", description = "APIs for managing process definitions and monitoring process instances")
public class ManagementController {

    private final ManagementService managementService;

    // --- Process Definitions ---

    @Operation(summary = "List all process definitions", description = "Retrieves a list of all deployed process definitions")
    @GetMapping("/definitions")
    public List<ProcessDefinitionResponse> listDefinitions() {
        return managementService.listDefinitions();
    }

    @Operation(summary = "Create process definition", description = "Creates a new process definition or updates an existing one if the name matches")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Process definition created"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload")
    })
    @PostMapping("/definitions")
    public ResponseEntity<ProcessDefinitionResponse> createDefinition(@RequestBody ProcessDefinitionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(managementService.createDefinition(request));
    }

    @Operation(summary = "Get process definition", description = "Retrieves a specific process definition by its name")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Process definition found"),
            @ApiResponse(responseCode = "404", description = "Process definition not found")
    })
    @GetMapping("/definitions/{name}")
    public ProcessDefinitionResponse getDefinition(@PathVariable String name) {
        return managementService.getDefinition(name);
    }

    @Operation(summary = "Update process definition", description = "Updates an existing process definition with new steps or transitions")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Process definition updated"),
            @ApiResponse(responseCode = "404", description = "Process definition not found")
    })
    @PutMapping("/definitions/{name}")
    public ProcessDefinitionResponse updateDefinition(@PathVariable String name,
            @RequestBody ProcessDefinitionRequest request) {
        return managementService.updateDefinition(name, request);
    }

    @Operation(summary = "Delete process definition", description = "Deletes a specific process definition by its name")
    @ApiResponse(responseCode = "204", description = "Process definition deleted successfully")
    @DeleteMapping("/definitions/{name}")
    public ResponseEntity<Void> deleteDefinition(@PathVariable String name) {
        managementService.deleteDefinition(name);
        return ResponseEntity.noContent().build();
    }

    // --- Process Instances ---

    @Operation(summary = "List process instances", description = "Retrieves a paginated list of running or completed process instances, optionally filtered by status and definition name")
    @GetMapping("/processes")
    public Page<ProcessInstanceResponse> listProcesses(
            @RequestParam(required = false) ProcessStatus status,
            @RequestParam(required = false) String definitionName,
            Pageable pageable) {
        return managementService.listProcesses(status, definitionName, pageable);
    }

    @Operation(summary = "Cancel process instance", description = "Cancels a running process instance by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Process cancelled successfully"),
            @ApiResponse(responseCode = "404", description = "Process instance not found")
    })
    @PostMapping("/processes/{id}/cancel")
    public ProcessInstanceResponse cancelProcess(@PathVariable UUID id) {
        return managementService.cancelProcess(id);
    }

    @Operation(summary = "Retry process instance", description = "Retries a failed process instance by its ID")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Process retry initiated"),
            @ApiResponse(responseCode = "404", description = "Process instance not found")
    })
    @PostMapping("/processes/{id}/retry")
    public ProcessInstanceResponse retryProcess(@PathVariable UUID id) {
        return managementService.retryProcess(id);
    }

    @Operation(summary = "Advance process instance", description = "Manually advances a stalled or waiting process instance")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Process advanced"),
            @ApiResponse(responseCode = "404", description = "Process instance not found")
    })
    @PostMapping("/processes/{id}/advance")
    public ProcessInstanceResponse advanceProcess(@PathVariable UUID id) {
        return managementService.advanceProcess(id);
    }

    @Operation(summary = "Force-wake a scheduled process",
               description = "Immediately wakes a SCHEDULED process, skipping the remaining timer delay and dispatching the pending step command")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Process woken successfully"),
            @ApiResponse(responseCode = "404", description = "Process instance not found"),
            @ApiResponse(responseCode = "409", description = "Process is not in SCHEDULED status")
    })
    @PostMapping("/processes/{id}/wake")
    public ProcessInstanceResponse wakeProcess(@PathVariable UUID id) {
        return managementService.wakeProcess(id);
    }

    @Operation(summary = "Signal a suspended process",
               description = "Injects a named signal event into a SUSPENDED process, resuming it from its current gate step")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Signal accepted, process resumed"),
            @ApiResponse(responseCode = "404", description = "Process instance not found"),
            @ApiResponse(responseCode = "409", description = "Process is not in SUSPENDED status")
    })
    @PostMapping("/processes/{id}/signal")
    public ProcessInstanceResponse signalProcess(@PathVariable UUID id,
                                                 @RequestBody SignalRequest request) {
        return managementService.signalProcess(id, request.event(), request.data());
    }

    @Operation(summary = "Get process audit trail",
               description = "Retrieves the ordered audit log for a process instance")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Audit trail returned"),
            @ApiResponse(responseCode = "404", description = "Process instance not found")
    })
    @GetMapping("/processes/{id}/audit")
    public List<AuditLogResponse> getAuditTrail(@PathVariable UUID id) {
        return managementService.getAuditTrail(id);
    }

    // --- Metrics ---

    @Operation(summary = "Get metrics summary", description = "Retrieves high-level metrics for all process instances in the system")
    @GetMapping("/metrics/summary")
    public MetricsSummaryResponse getMetricsSummary() {
        return managementService.getMetricsSummary();
    }
}
