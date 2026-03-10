package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class OutboxPublisherServiceTest {

    @Mock
    private OutboxEventRepository outboxRepository;

    @Mock
    private CommandPublisherService commandPublisher;

    @Mock
    private AuditLogService auditLogService;

    private OutboxPublisherService outboxPublisherService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        outboxPublisherService = new OutboxPublisherService(outboxRepository, commandPublisher, objectMapper, auditLogService);
    }

    @Test
    void publishesPendingEventsAndMarksPublished() {
        OutboxEvent event = new OutboxEvent("pid-1", "STEP_1", "{\"userId\":\"42\"}");
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));

        outboxPublisherService.publishPendingEvents();

        verify(commandPublisher).publishCommand(eq("pid-1"), eq("STEP_1"), any());
        assertThat(event.isPublished()).isTrue();
        assertThat(event.getPublishedAt()).isNotNull();
    }

    @Test
    void skipsOnKafkaFailure() {
        OutboxEvent event = new OutboxEvent("pid-2", "STEP_1", "{\"userId\":\"99\"}");
        when(outboxRepository.findByPublishedFalseOrderByCreatedAtAsc()).thenReturn(List.of(event));
        doThrow(new RuntimeException("Kafka unavailable")).when(commandPublisher)
                .publishCommand(any(), any(), any());

        outboxPublisherService.publishPendingEvents();

        assertThat(event.isPublished()).isFalse();
        assertThat(event.getPublishedAt()).isNull();
    }
}
