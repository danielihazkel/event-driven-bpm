package com.edoe.orchestrator.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
@Entity
@Table(name = "process_instances")
public class ProcessInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "definition_name", nullable = false)
    private String definitionName;

    /** The version of the process definition this instance was started with. */
    @Column(name = "definition_version", nullable = false)
    private int definitionVersion = 1;

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

    // --- Sub-Process / Call Activity state (Phase 10) ---

    /** UUID of the parent process that spawned this child. Null for top-level processes. */
    @Column(name = "parent_process_id")
    private UUID parentProcessId;

    /** The step the parent should advance to when this child process completes. */
    @Column(name = "parent_next_step")
    private String parentNextStep;

    // --- Multi-Instance / Scatter-Gather state (Phase 10) ---

    /** Base step name being multi-instanced (e.g. "PROCESS_ORDER"). Null when not in a scatter-gather wait. */
    @Column(name = "mi_step")
    private String miStep;

    /** JSON array of per-instance output maps. Null when not in a scatter-gather wait. */
    @Column(name = "mi_results", columnDefinition = "TEXT")
    private String miResults;

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

    public ProcessInstance(String definitionName, String currentStep, String contextData, ProcessStatus status) {
        this.definitionName = definitionName;
        this.definitionVersion = 1;
        this.currentStep = currentStep;
        this.contextData = contextData;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.stepStartedAt = LocalDateTime.now();
    }

    public ProcessInstance(String definitionName, int definitionVersion, String currentStep, String contextData, ProcessStatus status) {
        this.definitionName = definitionName;
        this.definitionVersion = definitionVersion;
        this.currentStep = currentStep;
        this.contextData = contextData;
        this.status = status;
        this.createdAt = LocalDateTime.now();
        this.stepStartedAt = LocalDateTime.now();
    }
}
