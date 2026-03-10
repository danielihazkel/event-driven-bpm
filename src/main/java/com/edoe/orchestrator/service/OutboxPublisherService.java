package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class OutboxPublisherService {

    private final OutboxEventRepository outboxRepository;
    private final CommandPublisherService commandPublisher;
    private final ObjectMapper objectMapper;
    private final AuditLogService auditLogService;

    @Scheduled(fixedDelayString = "${edoe.orchestrator.outbox-poll-interval-ms:1000}")
    @Transactional
    public void publishPendingEvents() {
        List<OutboxEvent> pending = outboxRepository.findByPublishedFalseOrderByCreatedAtAsc();
        for (OutboxEvent event : pending) {
            try {
                Map<String, Object> data = objectMapper.readValue(
                        event.getPayload(), new TypeReference<Map<String, Object>>() {});
                commandPublisher.publishCommand(event.getAggregateId(), event.getEventType(), data);
                event.setPublished(true);
                event.setPublishedAt(LocalDateTime.now());
                try {
                    auditLogService.record(UUID.fromString(event.getAggregateId()),
                            AuditEventType.COMMAND_DISPATCHED, event.getEventType(), null, null,
                            Map.of("outboxEventId", event.getId().toString()));
                } catch (IllegalArgumentException ignore) {
                    // aggregateId is not a valid UUID — skip audit
                }
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={} type={}: {}",
                        event.getId(), event.getEventType(), e.getMessage());
            }
        }
    }
}
