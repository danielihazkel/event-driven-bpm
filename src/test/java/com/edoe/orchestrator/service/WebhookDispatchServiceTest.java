package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.entity.WebhookSubscription;
import com.edoe.orchestrator.repository.WebhookSubscriptionRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestTemplate;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class WebhookDispatchServiceTest {

    @Mock
    private WebhookSubscriptionRepository webhookSubscriptionRepository;

    @Mock
    private RestTemplate restTemplate;

    @Mock
    private AuditLogService auditLogService;

    private WebhookDispatchService webhookDispatchService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        webhookDispatchService = new WebhookDispatchService(
                webhookSubscriptionRepository, restTemplate, objectMapper, auditLogService);
    }

    private WebhookSubscription subscription(String definitionName, String eventsJson, String secret) throws Exception {
        WebhookSubscription sub = new WebhookSubscription(definitionName, "https://example.com/hook", eventsJson, secret);
        Field idField = WebhookSubscription.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(sub, UUID.randomUUID());
        return sub;
    }

    @Test
    void completed_matchedSubscription_dispatchesPOST() throws Exception {
        UUID processId = UUID.randomUUID();
        WebhookSubscription sub = subscription("TEST_FLOW", "[\"COMPLETED\"]", null);
        when(webhookSubscriptionRepository.findActiveForDefinition("TEST_FLOW")).thenReturn(List.of(sub));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        webhookDispatchService.dispatchTerminalEvent(processId, "TEST_FLOW",
                ProcessStatus.COMPLETED, "{}", LocalDateTime.now());

        verify(restTemplate).exchange(eq("https://example.com/hook"), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void failed_matchedSubscription_dispatchesPOST() throws Exception {
        UUID processId = UUID.randomUUID();
        WebhookSubscription sub = subscription("TEST_FLOW", "[\"FAILED\"]", null);
        when(webhookSubscriptionRepository.findActiveForDefinition("TEST_FLOW")).thenReturn(List.of(sub));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        webhookDispatchService.dispatchTerminalEvent(processId, "TEST_FLOW",
                ProcessStatus.FAILED, "{}", LocalDateTime.now());

        verify(restTemplate).exchange(eq("https://example.com/hook"), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void cancelled_matchedSubscription_dispatchesPOST() throws Exception {
        UUID processId = UUID.randomUUID();
        WebhookSubscription sub = subscription("TEST_FLOW", "[\"CANCELLED\"]", null);
        when(webhookSubscriptionRepository.findActiveForDefinition("TEST_FLOW")).thenReturn(List.of(sub));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        webhookDispatchService.dispatchTerminalEvent(processId, "TEST_FLOW",
                ProcessStatus.CANCELLED, "{}", LocalDateTime.now());

        verify(restTemplate).exchange(eq("https://example.com/hook"), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void statusNotInSubscriptionFilter_noDispatch() throws Exception {
        UUID processId = UUID.randomUUID();
        WebhookSubscription sub = subscription("TEST_FLOW", "[\"COMPLETED\"]", null);
        when(webhookSubscriptionRepository.findActiveForDefinition("TEST_FLOW")).thenReturn(List.of(sub));

        webhookDispatchService.dispatchTerminalEvent(processId, "TEST_FLOW",
                ProcessStatus.FAILED, "{}", LocalDateTime.now());

        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
    }

    @Test
    void wildcardSubscription_matchesAnyDefinition() throws Exception {
        UUID processId = UUID.randomUUID();
        WebhookSubscription sub = subscription(null, "[\"COMPLETED\"]", null);
        when(webhookSubscriptionRepository.findActiveForDefinition("ANY_FLOW")).thenReturn(List.of(sub));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        webhookDispatchService.dispatchTerminalEvent(processId, "ANY_FLOW",
                ProcessStatus.COMPLETED, "{}", LocalDateTime.now());

        verify(restTemplate).exchange(eq("https://example.com/hook"), eq(HttpMethod.POST), any(), eq(String.class));
    }

    @Test
    void secretSet_signatureHeaderPresent() throws Exception {
        UUID processId = UUID.randomUUID();
        WebhookSubscription sub = subscription("TEST_FLOW", "[\"COMPLETED\"]", "my-secret");
        when(webhookSubscriptionRepository.findActiveForDefinition("TEST_FLOW")).thenReturn(List.of(sub));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("ok"));

        webhookDispatchService.dispatchTerminalEvent(processId, "TEST_FLOW",
                ProcessStatus.COMPLETED, "{}", LocalDateTime.now());

        ArgumentCaptor<org.springframework.http.HttpEntity> captor =
                ArgumentCaptor.forClass(org.springframework.http.HttpEntity.class);
        verify(restTemplate).exchange(anyString(), eq(HttpMethod.POST), captor.capture(), eq(String.class));
        String sigHeader = captor.getValue().getHeaders().getFirst("X-Webhook-Signature");
        assertThat(sigHeader).isNotNull().startsWith("sha256=");
    }

    @Test
    void allAttemptsFailure_auditWebhookFailed() throws Exception {
        UUID processId = UUID.randomUUID();
        WebhookSubscription sub = subscription("TEST_FLOW", "[\"COMPLETED\"]", null);
        when(webhookSubscriptionRepository.findActiveForDefinition("TEST_FLOW")).thenReturn(List.of(sub));
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenThrow(new RestClientException("connection refused"));

        webhookDispatchService.dispatchTerminalEvent(processId, "TEST_FLOW",
                ProcessStatus.COMPLETED, "{}", LocalDateTime.now());

        verify(auditLogService).record(eq(processId), eq(AuditEventType.WEBHOOK_FAILED),
                isNull(), isNull(), isNull(), any());
    }

    @Test
    void noMatchingSubscriptions_noHttpCallNoAudit() {
        UUID processId = UUID.randomUUID();
        when(webhookSubscriptionRepository.findActiveForDefinition("EMPTY_FLOW")).thenReturn(List.of());

        webhookDispatchService.dispatchTerminalEvent(processId, "EMPTY_FLOW",
                ProcessStatus.COMPLETED, "{}", LocalDateTime.now());

        verify(restTemplate, never()).exchange(anyString(), any(), any(), eq(String.class));
        verify(auditLogService, never()).record(any(), any(), any(), any(), any(), any());
    }
}
