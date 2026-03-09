package com.edoe.orchestrator.worker;

import com.edoe.orchestrator.config.KafkaTopicConfig;
import com.edoe.orchestrator.dto.OrchestratorMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@RequiredArgsConstructor
@Service
public class WorkerEventPublisherService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public void publishEvent(String processId, String eventType, Map<String, Object> outputData) {
        OrchestratorMessage message = new OrchestratorMessage(processId, eventType, outputData);
        try {
            String json = objectMapper.writeValueAsString(message);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopicConfig.WORKER_EVENTS_TOPIC,
                    null,
                    processId,
                    json
            );
            record.headers().add(new RecordHeader("processId", processId.getBytes(UTF_8)));
            kafkaTemplate.send(record);
            log.debug("Published event {} for process {}", eventType, processId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize event message for process " + processId, e);
        }
    }
}
