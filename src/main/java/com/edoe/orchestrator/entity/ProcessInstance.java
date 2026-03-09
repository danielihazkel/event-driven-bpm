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

    // --- Saga Rollback state (Phase 9) ---

    @Column(name = "compensating", nullable = false)
    private Boolean compensating = false;

    @Column(name = "completed_steps", columnDefinition = "TEXT")
    private String completedSteps = "[]";

    // --- Timer / Delay state (Phase 10) ---

    /** Timestamp at which a SCHEDULED process should be woken up. Null when not in a delay. */
    @Column(name = "wake_at")
    private LocalDateTime wakeAt;

    // --- Fork/Join state (Phase 7) ---

    /** Number of parallel branches still outstanding. Null when not in a fork. */
    @Column(name = "parallel_pending")
    private Integer parallelPending;

    /**
     * Step to dispatch once all parallel branches have completed. Null when not in
     * a fork.
     */
    @Column(name = "join_step")
    private String joinStep;

    /**
     * JSON array of eventTypes already received during parallel wait (for
     * idempotency).
     */
    @Column(name = "parallel_completed", columnDefinition = "TEXT")
    private String parallelCompleted;

    protected ProcessInstance() {
    }

    public ProcessInstance(String definitionName, String currentStep, String contextData, ProcessStatus status) {
        this.definitionName = definitionName;
        this.currentStep = currentStep;
        this.contextData = contextData;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.stepStartedAt = LocalDateTime.now();
    }

    public UUID getId() {
        return id;
    }

    public String getDefinitionName() {
        return definitionName;
    }

    public String getCurrentStep() {
        return currentStep;
    }

    public String getContextData() {
        return contextData;
    }

    public ProcessStatus getStatus() {
        return status;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getStepStartedAt() {
        return stepStartedAt;
    }

    public LocalDateTime getCompletedAt() {
        return completedAt;
    }

    public LocalDateTime getWakeAt() {
        return wakeAt;
    }

    public void setWakeAt(LocalDateTime wakeAt) {
        this.wakeAt = wakeAt;
    }

    public Integer getParallelPending() {
        return parallelPending;
    }

    public String getJoinStep() {
        return joinStep;
    }

    public String getParallelCompleted() {
        return parallelCompleted;
    }

    public Boolean getCompensating() {
        return compensating;
    }

    public String getCompletedSteps() {
        return completedSteps;
    }

    public void setCurrentStep(String currentStep) {
        this.currentStep = currentStep;
    }

    public void setContextData(String contextData) {
        this.contextData = contextData;
    }

    public void setStatus(ProcessStatus status) {
        this.status = status;
    }

    public void setStepStartedAt(LocalDateTime stepStartedAt) {
        this.stepStartedAt = stepStartedAt;
    }

    public void setCompletedAt(LocalDateTime completedAt) {
        this.completedAt = completedAt;
    }

    public void setParallelPending(Integer parallelPending) {
        this.parallelPending = parallelPending;
    }

    public void setJoinStep(String joinStep) {
        this.joinStep = joinStep;
    }

    public void setParallelCompleted(String parallelCompleted) {
        this.parallelCompleted = parallelCompleted;
    }

    public void setCompensating(Boolean compensating) {
        this.compensating = compensating;
    }

    public void setCompletedSteps(String completedSteps) {
        this.completedSteps = completedSteps;
    }
}
