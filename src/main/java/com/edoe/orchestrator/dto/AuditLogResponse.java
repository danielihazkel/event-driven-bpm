package com.edoe.orchestrator.dto;

import com.edoe.orchestrator.entity.AuditEventType;

import java.time.LocalDateTime;
import java.util.UUID;

public record AuditLogResponse(
        UUID id,
        UUID processId,
        AuditEventType eventType,
        String stepName,
        String fromStatus,
        String toStatus,
        String payload,
        LocalDateTime occurredAt
) {}
