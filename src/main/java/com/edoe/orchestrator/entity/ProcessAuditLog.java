package com.edoe.orchestrator.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;
import java.util.UUID;

@Getter
@NoArgsConstructor(access = lombok.AccessLevel.PROTECTED)
@Entity
@Table(name = "process_audit_logs", indexes = @Index(columnList = "process_id"))
public class ProcessAuditLog {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "process_id", nullable = false)
    private UUID processId;

    @Enumerated(EnumType.STRING)
    @Column(name = "event_type")
    private AuditEventType eventType;

    @Column(name = "step_name")
    private String stepName;

    @Column(name = "from_status")
    private String fromStatus;

    @Column(name = "to_status")
    private String toStatus;

    @Column(name = "payload", columnDefinition = "TEXT")
    private String payload;

    @Column(name = "occurred_at", nullable = false)
    private LocalDateTime occurredAt;

    public ProcessAuditLog(UUID processId, AuditEventType eventType, String stepName,
                           String fromStatus, String toStatus, String payload) {
        this.processId = processId;
        this.eventType = eventType;
        this.stepName = stepName;
        this.fromStatus = fromStatus;
        this.toStatus = toStatus;
        this.payload = payload;
        this.occurredAt = LocalDateTime.now();
    }
}
