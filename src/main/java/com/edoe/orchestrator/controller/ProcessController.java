package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.dto.StartFlowRequest;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import com.edoe.orchestrator.service.TransitionService;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
public class ProcessController {

    private final TransitionService transitionService;
    private final ProcessInstanceRepository repository;

    public ProcessController(TransitionService transitionService, ProcessInstanceRepository repository) {
        this.transitionService = transitionService;
        this.repository = repository;
    }

    @PostMapping("/start-flow")
    public ResponseEntity<Map<String, String>> startFlow(@RequestBody StartFlowRequest request) {
        UUID processId = transitionService.startProcess(request.definitionName(), request.initialData());
        return ResponseEntity.status(201).body(Map.of("processId", processId.toString()));
    }

    @GetMapping("/status/{id}")
    public ResponseEntity<?> getStatus(@PathVariable UUID id) {
        return repository.findById(id)
                .<ResponseEntity<?>>map(p -> ResponseEntity.ok(Map.of(
                        "processId", p.getId().toString(),
                        "status",    p.getStatus(),
                        "step",      p.getCurrentStep(),
                        "context",   p.getContextData()
                )))
                .orElse(ResponseEntity.notFound().build());
    }
}
