package com.edoe.orchestrator.service;

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
public class CommandPublisherService {

    private final KafkaTemplate<String, String> kafkaTemplate;
    private final ObjectMapper objectMapper;

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
