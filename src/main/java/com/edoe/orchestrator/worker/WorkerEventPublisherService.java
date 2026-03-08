package com.edoe.orchestrator.worker;

import com.edoe.orchestrator.config.KafkaTopicConfig;
import com.edoe.orchestrator.dto.OrchestratorMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.common.header.internals.RecordHeader;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Service
public class WorkerEventPublisherService {

    private static final Logger log = LoggerFactory.getLogger(WorkerEventPublisherService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public WorkerEventPublisherService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

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
