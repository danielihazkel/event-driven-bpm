package com.edoe.orchestrator.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "human_tasks")
@Getter
@Setter
@NoArgsConstructor
public class HumanTask {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "process_instance_id", nullable = false)
    private UUID processInstanceId;

    @Column(name = "process_definition_name", nullable = false)
    private String processDefinitionName;

    @Column(name = "task_name", nullable = false)
    private String taskName;

    @Column(name = "signal_event", nullable = false)
    private String signalEvent;

    /** JSON-serialized Map<String, Object> form specification. */
    @Column(name = "form_schema", columnDefinition = "TEXT")
    private String formSchema;

    @Column(name = "assignee")
    private String assignee;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false)
    private HumanTaskStatus status;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "completed_at")
    private LocalDateTime completedAt;

    /** JSON-serialized result data submitted by the human on completion. */
    @Column(name = "result_data", columnDefinition = "TEXT")
    private String resultData;

    public HumanTask(UUID processInstanceId, String processDefinitionName,
                     String taskName, String signalEvent,
                     String formSchema, String assignee) {
        this.processInstanceId = processInstanceId;
        this.processDefinitionName = processDefinitionName;
        this.taskName = taskName;
        this.signalEvent = signalEvent;
        this.formSchema = formSchema;
        this.assignee = assignee;
        this.status = HumanTaskStatus.PENDING;
        this.createdAt = LocalDateTime.now();
    }
}
