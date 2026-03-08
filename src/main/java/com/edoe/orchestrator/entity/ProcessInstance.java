package com.edoe.orchestrator.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "process_instances")
public class ProcessInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "definition_name", nullable = false)
    private String definitionName;

    @Column(name = "current_step", nullable = false)
    private String currentStep;

    @Column(name = "context_data", columnDefinition = "TEXT")
    private String contextData;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ProcessStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "step_started_at", nullable = false)
    private LocalDateTime stepStartedAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    protected ProcessInstance() {}

    public ProcessInstance(String definitionName, String currentStep, String contextData, ProcessStatus status) {
        this.definitionName = definitionName;
        this.currentStep = currentStep;
        this.contextData = contextData;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.stepStartedAt = LocalDateTime.now();
    }

    public UUID getId() { return id; }
    public String getDefinitionName() { return definitionName; }
    public String getCurrentStep() { return currentStep; }
    public String getContextData() { return contextData; }
    public ProcessStatus getStatus() { return status; }
    public LocalDateTime getCreatedAt() { return createdAt; }
    public LocalDateTime getStepStartedAt() { return stepStartedAt; }
    public LocalDateTime getCompletedAt() { return completedAt; }

    public void setCurrentStep(String currentStep) { this.currentStep = currentStep; }
    public void setContextData(String contextData) { this.contextData = contextData; }
    public void setStatus(ProcessStatus status) { this.status = status; }
    public void setStepStartedAt(LocalDateTime stepStartedAt) { this.stepStartedAt = stepStartedAt; }
    public void setCompletedAt(LocalDateTime completedAt) { this.completedAt = completedAt; }
}
