package com.edoe.orchestrator.worker;

import com.edoe.orchestrator.config.KafkaTopicConfig;
import com.edoe.orchestrator.dto.OrchestratorMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

import static java.nio.charset.StandardCharsets.UTF_8;

@Component
public class WorkerCommandListener {

    private static final Logger log = LoggerFactory.getLogger(WorkerCommandListener.class);

    private final List<WorkerTask> tasks;
    private final WorkerEventPublisherService publisherService;
    private final ObjectMapper objectMapper;

    public WorkerCommandListener(List<WorkerTask> tasks,
                                 WorkerEventPublisherService publisherService,
                                 ObjectMapper objectMapper) {
        this.tasks = tasks;
        this.publisherService = publisherService;
        this.objectMapper = objectMapper;
    }

    @KafkaListener(topics = KafkaTopicConfig.ORCHESTRATOR_COMMANDS_TOPIC, groupId = "worker-group")
    public void onCommand(ConsumerRecord<String, String> record) {
        OrchestratorMessage message;
        try {
            message = objectMapper.readValue(record.value(), OrchestratorMessage.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize command", e);
        }

        WorkerTask task = tasks.stream()
                .filter(t -> t.taskType().equals(message.type()))
                .findFirst()
                .orElse(null);

        if (task == null) {
            log.warn("No worker task found for type '{}', skipping. processId={}", message.type(), message.processId());
            return;
        }

        String processId = extractProcessId(record, message);
        Map<String, Object> output = task.execute(message.data());
        publisherService.publishEvent(processId, message.type() + "_FINISHED", output);
    }

    private String extractProcessId(ConsumerRecord<String, String> record, OrchestratorMessage message) {
        Header header = record.headers().lastHeader("processId");
        if (header != null) {
            return new String(header.value(), UTF_8);
        }
        return message.processId();
    }
}
