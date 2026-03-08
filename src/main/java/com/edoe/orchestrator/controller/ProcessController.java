package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.dto.StartFlowRequest;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import com.edoe.orchestrator.service.TransitionService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.responses.ApiResponses;
import io.swagger.v3.oas.annotations.tags.Tag;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@Tag(name = "Process Execution", description = "APIs for executing and checking the status of individual process flows")
public class ProcessController {

    private final TransitionService transitionService;
    private final ProcessInstanceRepository repository;

    public ProcessController(TransitionService transitionService, ProcessInstanceRepository repository) {
        this.transitionService = transitionService;
        this.repository = repository;
    }

    @Operation(summary = "Start a new process flow", description = "Instantiates a new process based on the given definition name and initial data")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "201", description = "Process started successfully"),
            @ApiResponse(responseCode = "404", description = "Process definition not found"),
            @ApiResponse(responseCode = "400", description = "Invalid request payload")
    })
    @PostMapping("/start-flow")
    public ResponseEntity<Map<String, String>> startFlow(@RequestBody StartFlowRequest request) {
        UUID processId = transitionService.startProcess(request.definitionName(), request.initialData());
        return ResponseEntity.status(201).body(Map.of("processId", processId.toString()));
    }

    @Operation(summary = "Check process status", description = "Retrieves the current execution status and context of a running or completed process instance")
    @ApiResponses(value = {
            @ApiResponse(responseCode = "200", description = "Process status retrieved"),
            @ApiResponse(responseCode = "404", description = "Process instance not found")
    })
    @GetMapping("/status/{id}")
    public ResponseEntity<?> getStatus(@PathVariable UUID id) {
        return repository.findById(id)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of(
                        "processId", p.getId().toString(),
                        "status", p.getStatus(),
                        "step", p.getCurrentStep(),
                        "context", p.getContextData())))
                .orElse(ResponseEntity.notFound().build());
    }
}
