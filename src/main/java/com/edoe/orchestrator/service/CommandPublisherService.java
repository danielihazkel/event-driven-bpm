package com.edoe.orchestrator.service;

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
public class CommandPublisherService {

    private static final Logger log = LoggerFactory.getLogger(CommandPublisherService.class);

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

    public CommandPublisherService(KafkaTemplate<String, String> kafkaTemplate, ObjectMapper objectMapper) {
        this.kafkaTemplate = kafkaTemplate;
        this.objectMapper = objectMapper;
    }

    public void publishCommand(String processId, String commandType, Map<String, Object> data) {
        OrchestratorMessage message = new OrchestratorMessage(processId, commandType, data);
        try {
            String json = objectMapper.writeValueAsString(message);
            ProducerRecord<String, String> record = new ProducerRecord<>(
                    KafkaTopicConfig.ORCHESTRATOR_COMMANDS_TOPIC,
                    null,
                    processId,
                    json
            );
            record.headers().add(new RecordHeader("processId", processId.getBytes(UTF_8)));
            kafkaTemplate.send(record);
            log.debug("Published command {} for process {}", commandType, processId);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize command message for process " + processId, e);
        }
    }
}
