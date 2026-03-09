package com.edoe.orchestrator.listener;

import com.edoe.orchestrator.config.KafkaTopicConfig;
import com.edoe.orchestrator.dto.OrchestratorMessage;
import com.edoe.orchestrator.service.TransitionService;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@RequiredArgsConstructor
@Component
public class WorkerEventListener {

    private final TransitionService transitionService;
    private final ObjectMapper objectMapper;

    @KafkaListener(
            topics = KafkaTopicConfig.WORKER_EVENTS_TOPIC,
            groupId = "${spring.kafka.consumer.group-id}"
    )
    public void onWorkerEvent(ConsumerRecord<String, String> record) {
        OrchestratorMessage message;
        try {
            message = objectMapper.readValue(record.value(), OrchestratorMessage.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize worker event", e);
        }

        String processId = extractProcessId(record, message);
        if (processId == null) {
            log.error("No processId found in event, skipping. offset={}", record.offset());
            return;
        }

        transitionService.handleEvent(processId, message.type(), message.data());
    }

    private String extractProcessId(ConsumerRecord<String, String> record, OrchestratorMessage message) {
        Header header = record.headers().lastHeader("processId");
        if (header != null) {
            return new String(header.value(), UTF_8);
        }
        return message.processId();
    }
}
