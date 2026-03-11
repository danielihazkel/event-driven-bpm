package com.edoe.orchestrator.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public record WebhookSubscriptionResponse(
        UUID id,
        String processDefinitionName,
        String targetUrl,
        List<String> events,
        boolean active,
        LocalDateTime createdAt
) {}
