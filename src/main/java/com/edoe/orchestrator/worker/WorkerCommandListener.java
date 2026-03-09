package com.edoe.orchestrator.worker;

import com.edoe.orchestrator.config.KafkaTopicConfig;
import com.edoe.orchestrator.dto.OrchestratorMessage;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static java.nio.charset.StandardCharsets.UTF_8;

@Slf4j
@RequiredArgsConstructor
@Component
public class WorkerCommandListener {

    /** Matches a multi-instance indexed step name, e.g. {@code PROCESS_ORDER__MI__2}. */
    private static final Pattern MI_SUFFIX = Pattern.compile("^(.+)__MI__\\d+$");

    private final List<WorkerTask> tasks;
    private final WorkerEventPublisherService publisherService;
    private final ObjectMapper objectMapper;

    @KafkaListener(topics = KafkaTopicConfig.ORCHESTRATOR_COMMANDS_TOPIC, groupId = "worker-group")
    public void onCommand(ConsumerRecord<String, String> record) {
        OrchestratorMessage message;
        try {
            message = objectMapper.readValue(record.value(), OrchestratorMessage.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to deserialize command", e);
        }

        WorkerTask task = findTask(message.type());

        if (task == null) {
            log.warn("No worker task found for type '{}', skipping. processId={}", message.type(), message.processId());
            return;
        }

        String processId = extractProcessId(record, message);
        Map<String, Object> output = task.execute(message.data());
        publisherService.publishEvent(processId, message.type() + "_FINISHED", output);
    }

    /**
     * Two-pass task lookup:
     * 1. Exact match on {@code messageType}.
     * 2. Strip the {@code __MI__N} suffix (multi-instance) and retry.
     */
    private WorkerTask findTask(String messageType) {
        WorkerTask task = tasks.stream()
                .filter(t -> t.taskType().equals(messageType))
                .findFirst()
                .orElse(null);
        if (task != null) return task;

        Matcher m = MI_SUFFIX.matcher(messageType);
        if (m.matches()) {
            String baseType = m.group(1);
            return tasks.stream()
                    .filter(t -> t.taskType().equals(baseType))
                    .findFirst()
                    .orElse(null);
        }
        return null;
    }

    private String extractProcessId(ConsumerRecord<String, String> record, OrchestratorMessage message) {
        Header header = record.headers().lastHeader("processId");
        if (header != null) {
            return new String(header.value(), UTF_8);
        }
        return message.processId();
    }
}
