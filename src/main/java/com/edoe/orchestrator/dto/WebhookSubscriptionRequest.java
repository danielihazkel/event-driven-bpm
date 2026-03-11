package com.edoe.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

@JsonInclude(JsonInclude.Include.NON_NULL)
public record WebhookSubscriptionRequest(
        String processDefinitionName,
        String targetUrl,
        List<String> events,
        String secret
) {}
