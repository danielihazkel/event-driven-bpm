package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class TimerServiceTest {

    @Mock
    private ProcessInstanceRepository instanceRepository;

    @Mock
    private OutboxEventRepository outboxRepository;

    private TimerService timerService;

    @BeforeEach
    void setUp() {
        timerService = new TimerService(instanceRepository, outboxRepository);
    }

    private ProcessInstance scheduledInstance(String step, LocalDateTime wakeAt) throws Exception {
        ProcessInstance inst = new ProcessInstance("DELAY_FLOW", step, "{\"key\":\"val\"}", ProcessStatus.SCHEDULED);
        Field idField = ProcessInstance.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(inst, UUID.randomUUID());
        inst.setWakeAt(wakeAt);
        return inst;
    }

    // 1. No expired timers — nothing happens
    @Test
    void doesNothing_whenNoExpiredTimers() {
        when(instanceRepository.findByStatusAndWakeAtLessThanEqual(eq(ProcessStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of());

        timerService.wakeExpiredTimers();

        verify(instanceRepository).findByStatusAndWakeAtLessThanEqual(eq(ProcessStatus.SCHEDULED), any());
        verifyNoMoreInteractions(instanceRepository);
        verifyNoInteractions(outboxRepository);
    }

    // 2. Expired timer found — process transitions to RUNNING, outbox event created
    @Test
    void wakesProcess_whenTimerElapsed() throws Exception {
        ProcessInstance inst = scheduledInstance("PROCESS_REQUEST", LocalDateTime.now().minusSeconds(10));
        when(instanceRepository.findByStatusAndWakeAtLessThanEqual(eq(ProcessStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of(inst));
        when(instanceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        timerService.wakeExpiredTimers();

        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(inst.getWakeAt()).isNull();

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("PROCESS_REQUEST");
    }

    // 3. Multiple expired timers — each woken independently
    @Test
    void wakesAllExpiredProcesses_whenMultipleDue() throws Exception {
        ProcessInstance inst1 = scheduledInstance("STEP_A", LocalDateTime.now().minusSeconds(5));
        ProcessInstance inst2 = scheduledInstance("STEP_B", LocalDateTime.now().minusSeconds(2));
        when(instanceRepository.findByStatusAndWakeAtLessThanEqual(eq(ProcessStatus.SCHEDULED), any(LocalDateTime.class)))
                .thenReturn(List.of(inst1, inst2));
        when(instanceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        timerService.wakeExpiredTimers();

        assertThat(inst1.getStatus()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(inst2.getStatus()).isEqualTo(ProcessStatus.RUNNING);
        verify(outboxRepository, times(2)).save(any(OutboxEvent.class));
    }
}
