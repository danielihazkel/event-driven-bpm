package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.WebhookSubscriptionRequest;
import com.edoe.orchestrator.dto.WebhookSubscriptionResponse;
import com.edoe.orchestrator.entity.WebhookSubscription;
import com.edoe.orchestrator.repository.WebhookSubscriptionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.NoSuchElementException;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class WebhookService {

    private static final Set<String> VALID_EVENTS = Set.of("COMPLETED", "FAILED", "CANCELLED");

    private final WebhookSubscriptionRepository webhookSubscriptionRepository;
    private final ObjectMapper objectMapper;

    @Transactional
    public WebhookSubscriptionResponse createSubscription(WebhookSubscriptionRequest request) {
        if (request.targetUrl() == null || request.targetUrl().isBlank()) {
            throw new IllegalArgumentException("targetUrl must not be blank");
        }
        if (request.events() == null || request.events().isEmpty()) {
            throw new IllegalArgumentException("events must not be empty");
        }
        for (String event : request.events()) {
            if (!VALID_EVENTS.contains(event)) {
                throw new IllegalArgumentException(
                        "Invalid event '" + event + "'. Valid events: " + VALID_EVENTS);
            }
        }

        String eventsJson = serializeEvents(request.events());
        WebhookSubscription sub = new WebhookSubscription(
                request.processDefinitionName(),
                request.targetUrl(),
                eventsJson,
                request.secret());
        return toResponse(webhookSubscriptionRepository.save(sub));
    }

    public Page<WebhookSubscriptionResponse> listSubscriptions(Pageable pageable) {
        return webhookSubscriptionRepository.findAll(pageable).map(this::toResponse);
    }

    public WebhookSubscriptionResponse getSubscription(UUID id) {
        return webhookSubscriptionRepository.findById(id)
                .map(this::toResponse)
                .orElseThrow(() -> new NoSuchElementException("Webhook subscription not found: " + id));
    }

    @Transactional
    public void deleteSubscription(UUID id) {
        if (!webhookSubscriptionRepository.existsById(id)) {
            throw new NoSuchElementException("Webhook subscription not found: " + id);
        }
        webhookSubscriptionRepository.deleteById(id);
    }

    @Transactional
    public WebhookSubscriptionResponse toggleActive(UUID id) {
        WebhookSubscription sub = webhookSubscriptionRepository.findById(id)
                .orElseThrow(() -> new NoSuchElementException("Webhook subscription not found: " + id));
        sub.setActive(!sub.isActive());
        return toResponse(webhookSubscriptionRepository.save(sub));
    }

    private WebhookSubscriptionResponse toResponse(WebhookSubscription sub) {
        List<String> events = deserializeEvents(sub.getEvents());
        return new WebhookSubscriptionResponse(
                sub.getId(),
                sub.getProcessDefinitionName(),
                sub.getTargetUrl(),
                events,
                sub.isActive(),
                sub.getCreatedAt());
    }

    private String serializeEvents(List<String> events) {
        try {
            return objectMapper.writeValueAsString(events);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize events", e);
        }
    }

    private List<String> deserializeEvents(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return objectMapper.readValue(json, new TypeReference<>() {});
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize events", e);
        }
    }
}
