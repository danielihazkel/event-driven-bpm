package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TransitionServiceTest {

    @Mock
    private ProcessInstanceRepository repository;

    @Mock
    private OutboxEventRepository outboxRepository;

    private TransitionService transitionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        transitionService = new TransitionService(repository, outboxRepository, objectMapper);
    }

    private ProcessInstance instanceWithId(UUID id, String step, ProcessStatus status, String contextJson) throws Exception {
        ProcessInstance inst = new ProcessInstance("TEST_FLOW", step, contextJson, status);
        Field field = ProcessInstance.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(inst, id);
        return inst;
    }

    // 1. startProcess saves with correct initial state and saves OutboxEvent for STEP_1
    @Test
    void startProcess_savesInstanceAndCreatesOutboxEvent() {
        UUID id = UUID.randomUUID();
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            ProcessInstance pi = inv.getArgument(0);
            try {
                Field f = ProcessInstance.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(pi, id);
            } catch (Exception e) { throw new RuntimeException(e); }
            return pi;
        });

        Map<String, Object> data = Map.of("userId", "user-42");
        UUID result = transitionService.startProcess("USER_REGISTRATION", data);

        assertThat(result).isEqualTo(id);

        ArgumentCaptor<ProcessInstance> captor = ArgumentCaptor.forClass(ProcessInstance.class);
        verify(repository).saveAndFlush(captor.capture());
        ProcessInstance saved = captor.getValue();
        assertThat(saved.getCurrentStep()).isEqualTo("STEP_1");
        assertThat(saved.getStatus()).isEqualTo(ProcessStatus.RUNNING);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        OutboxEvent outbox = outboxCaptor.getValue();
        assertThat(outbox.getAggregateId()).isEqualTo(id.toString());
        assertThat(outbox.getEventType()).isEqualTo("STEP_1");
    }

    // 2. handleEvent STEP_1_FINISHED → transitions to STEP_2, saves OutboxEvent for STEP_2
    @Test
    void handleEvent_step1Finished_transitionsToStep2() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "STEP_1", ProcessStatus.RUNNING, "{\"userId\":\"user-42\"}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        transitionService.handleEvent(id.toString(), "STEP_1_FINISHED", Map.of("result", "ok"));

        assertThat(inst.getCurrentStep()).isEqualTo("STEP_2");
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.RUNNING);
        verify(repository).saveAndFlush(inst);

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("STEP_2");
    }

    // 3. handleEvent STEP_2_FINISHED → marks COMPLETED, no OutboxEvent saved
    @Test
    void handleEvent_step2Finished_completesProcess() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "STEP_2", ProcessStatus.RUNNING, "{\"userId\":\"user-42\"}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        transitionService.handleEvent(id.toString(), "STEP_2_FINISHED", Map.of("finalResult", "done"));

        assertThat(inst.getCurrentStep()).isEqualTo("COMPLETED");
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        verify(repository).saveAndFlush(inst);
        verifyNoInteractions(outboxRepository);
    }

    // 4. handleEvent duplicate — stale STEP_1_FINISHED when already on STEP_2 → silently ignored
    @Test
    void handleEvent_staleEvent_ignored() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "STEP_2", ProcessStatus.RUNNING, "{}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));

        transitionService.handleEvent(id.toString(), "STEP_1_FINISHED", Map.of("result", "ok"));

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxRepository);
    }

    // 5. handleEvent already COMPLETED → ignored
    @Test
    void handleEvent_alreadyCompleted_ignored() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "COMPLETED", ProcessStatus.COMPLETED, "{}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));

        transitionService.handleEvent(id.toString(), "STEP_2_FINISHED", Map.of());

        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxRepository);
    }

    // 6. handleEvent unknown processId → throws IllegalArgumentException
    @Test
    void handleEvent_unknownProcessId_throwsException() {
        UUID id = UUID.randomUUID();
        when(repository.findById(id)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transitionService.handleEvent(id.toString(), "STEP_1_FINISHED", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining(id.toString());
    }

    // 7. handleEvent merges output into existing context_data
    @Test
    void handleEvent_mergesContextData() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "STEP_1", ProcessStatus.RUNNING, "{\"userId\":\"user-42\"}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        transitionService.handleEvent(id.toString(), "STEP_1_FINISHED", Map.of("result", "ok"));

        String mergedJson = inst.getContextData();
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(mergedJson, Map.class);
        assertThat(merged).containsEntry("userId", "user-42");
        assertThat(merged).containsEntry("result", "ok");
    }
}
