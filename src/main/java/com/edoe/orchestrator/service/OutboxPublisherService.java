package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

@Service
public class OutboxPublisherService {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisherService.class);

    private final OutboxEventRepository outboxRepository;
    private final CommandPublisherService commandPublisher;
    private final ObjectMapper objectMapper;

    public OutboxPublisherService(OutboxEventRepository outboxRepository,
                                  CommandPublisherService commandPublisher,
                                  ObjectMapper objectMapper) {
        this.outboxRepository = outboxRepository;
        this.commandPublisher = commandPublisher;
        this.objectMapper = objectMapper;
    }

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
            } catch (Exception e) {
                log.error("Failed to publish outbox event id={} type={}: {}",
                        event.getId(), event.getEventType(), e.getMessage());
            }
        }
    }
}
