package com.edoe.orchestrator.worker;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

class WorkerCommandListenerTest {

    private WorkerEventPublisherService publisherService;
    private WorkerCommandListener listener;
    private ObjectMapper objectMapper;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        publisherService = mock(WorkerEventPublisherService.class);

        WorkerTask stepOneTask = new StepOneWorkerTask() {
            @Override
            public Map<String, Object> execute(Map<String, Object> inputData) {
                return Map.of("step1Result", "done");
            }
        };

        listener = new WorkerCommandListener(List.of(stepOneTask), publisherService, objectMapper);
    }

    @Test
    void dispatchesToMatchingTask() throws Exception {
        String processId = "proc-123";
        String json = objectMapper.writeValueAsString(
                Map.of("processId", processId, "type", "STEP_1", "data", Map.of("userId", "42"))
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orchestrator-commands", 0, 0L, processId, json);

        listener.onCommand(record);

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisherService).publishEvent(eq(processId), eventTypeCaptor.capture(), any());
        assertThat(eventTypeCaptor.getValue()).isEqualTo("STEP_1_FINISHED");
    }

    @Test
    void skipsUnknownTaskType() throws Exception {
        String processId = "proc-456";
        String json = objectMapper.writeValueAsString(
                Map.of("processId", processId, "type", "UNKNOWN_STEP", "data", Map.of())
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orchestrator-commands", 0, 1L, processId, json);

        listener.onCommand(record);

        verifyNoInteractions(publisherService);
    }

    @Test
    void dispatchesToMatchingTaskWithMiSuffix() throws Exception {
        // Multi-instance indexed type STEP_1__MI__0 should match the STEP_1 task
        // and respond with STEP_1__MI__0_FINISHED (preserving the full indexed type)
        String processId = "proc-mi-789";
        String json = objectMapper.writeValueAsString(
                Map.of("processId", processId, "type", "STEP_1__MI__0", "data", Map.of("__miIndex", 0))
        );
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orchestrator-commands", 0, 3L, processId, json);

        listener.onCommand(record);

        ArgumentCaptor<String> eventTypeCaptor = ArgumentCaptor.forClass(String.class);
        verify(publisherService).publishEvent(eq(processId), eventTypeCaptor.capture(), any());
        assertThat(eventTypeCaptor.getValue()).isEqualTo("STEP_1__MI__0_FINISHED");
    }

    @Test
    void throwsOnDeserializationError() {
        ConsumerRecord<String, String> record = new ConsumerRecord<>("orchestrator-commands", 0, 2L, "key", "not-valid-json{{{");

        assertThatThrownBy(() -> listener.onCommand(record))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("Failed to deserialize command");

        verifyNoInteractions(publisherService);
    }
}
