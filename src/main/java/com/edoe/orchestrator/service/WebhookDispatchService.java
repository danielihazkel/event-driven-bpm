package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.entity.WebhookSubscription;
import com.edoe.orchestrator.repository.WebhookSubscriptionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.time.LocalDateTime;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class WebhookDispatchService {

    private static final Set<ProcessStatus> TERMINAL_STATUSES =
            EnumSet.of(ProcessStatus.COMPLETED, ProcessStatus.FAILED, ProcessStatus.CANCELLED);
    private static final int[] BACKOFF_MS = {0, 1000, 3000};

    private final WebhookSubscriptionRepository webhookSubscriptionRepository;
    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Async
    public void dispatchTerminalEvent(UUID processId, String definitionName,
                                      ProcessStatus status, String contextData,
                                      LocalDateTime completedAt) {
        if (!TERMINAL_STATUSES.contains(status)) {
            return;
        }

        List<WebhookSubscription> candidates =
                webhookSubscriptionRepository.findActiveForDefinition(definitionName);

        List<WebhookSubscription> matched = candidates.stream()
                .filter(sub -> eventsListContains(sub.getEvents(), status.name()))
                .toList();

        if (matched.isEmpty()) {
            return;
        }

        String payloadJson;
        try {
            payloadJson = objectMapper.writeValueAsString(Map.of(
                    "processId", processId.toString(),
                    "definitionName", definitionName,
                    "status", status.name(),
                    "contextData", contextData != null ? contextData : "{}",
                    "completedAt", completedAt != null ? completedAt.toString() : "",
                    "timestamp", LocalDateTime.now().toString()
            ));
        } catch (JsonProcessingException e) {
            log.error("WebhookDispatchService: failed to serialize payload for process {}: {}", processId, e.getMessage());
            return;
        }

        for (WebhookSubscription sub : matched) {
            dispatchWithRetry(sub, payloadJson, processId);
        }
    }

    private void dispatchWithRetry(WebhookSubscription sub, String payloadJson, UUID processId) {
        Exception lastException = null;

        for (int attempt = 0; attempt < BACKOFF_MS.length; attempt++) {
            if (BACKOFF_MS[attempt] > 0) {
                try {
                    Thread.sleep(BACKOFF_MS[attempt]);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }

            try {
                HttpHeaders headers = new HttpHeaders();
                headers.setContentType(MediaType.APPLICATION_JSON);

                if (sub.getSecret() != null && !sub.getSecret().isBlank()) {
                    String signature = computeHmac(payloadJson, sub.getSecret());
                    headers.set("X-Webhook-Signature", "sha256=" + signature);
                }

                HttpEntity<String> entity = new HttpEntity<>(payloadJson, headers);
                var response = restTemplate.exchange(sub.getTargetUrl(), HttpMethod.POST, entity, String.class);
                int httpStatus = response.getStatusCode().value();

                auditLogService.record(processId, AuditEventType.WEBHOOK_DISPATCHED, null,
                        null, null,
                        Map.of("targetUrl", sub.getTargetUrl(), "attempt", attempt + 1, "httpStatus", httpStatus));
                log.debug("Webhook dispatched to {} for process {} (attempt {}, status {})",
                        sub.getTargetUrl(), processId, attempt + 1, httpStatus);
                return;

            } catch (Exception e) {
                lastException = e;
                log.warn("Webhook attempt {}/{} failed for {} (process {}): {}",
                        attempt + 1, BACKOFF_MS.length, sub.getTargetUrl(), processId, e.getMessage());
            }
        }

        auditLogService.record(processId, AuditEventType.WEBHOOK_FAILED, null,
                null, null,
                Map.of("targetUrl", sub.getTargetUrl(),
                        "error", lastException != null ? lastException.getMessage() : "unknown",
                        "attempts", BACKOFF_MS.length));
        log.error("Webhook permanently failed for {} (process {}) after {} attempts",
                sub.getTargetUrl(), processId, BACKOFF_MS.length);
    }

    private boolean eventsListContains(String eventsJson, String statusName) {
        if (eventsJson == null || eventsJson.isBlank()) {
            return false;
        }
        try {
            List<String> events = objectMapper.readValue(eventsJson, new TypeReference<>() {});
            return events.contains(statusName);
        } catch (JsonProcessingException e) {
            log.warn("WebhookDispatchService: failed to parse events JSON '{}': {}", eventsJson, e.getMessage());
            return false;
        }
    }

    private String computeHmac(String payload, String secret) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            SecretKeySpec keySpec = new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256");
            mac.init(keySpec);
            byte[] hmacBytes = mac.doFinal(payload.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hmacBytes);
        } catch (Exception e) {
            throw new RuntimeException("Failed to compute HMAC signature", e);
        }
    }
}
