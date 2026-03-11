package com.edoe.orchestrator.dto;

import com.edoe.orchestrator.entity.HumanTaskStatus;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.UUID;

public record HumanTaskResponse(
        UUID id,
        UUID processInstanceId,
        String processDefinitionName,
        String taskName,
        String signalEvent,
        Map<String, Object> formSchema,
        String assignee,
        HumanTaskStatus status,
        LocalDateTime createdAt,
        LocalDateTime completedAt,
        Map<String, Object> resultData) {
}
