package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class StepTimeoutServiceTest {

    @Mock
    private ProcessInstanceRepository repository;

    private StepTimeoutService stepTimeoutService;

    @BeforeEach
    void setUp() {
        stepTimeoutService = new StepTimeoutService(repository, 30L);
    }

    private ProcessInstance runningInstance(LocalDateTime stepStartedAt) throws Exception {
        ProcessInstance inst = new ProcessInstance("TEST_FLOW", "STEP_1", "{}", ProcessStatus.RUNNING);
        Field idField = ProcessInstance.class.getDeclaredField("id");
        idField.setAccessible(true);
        idField.set(inst, UUID.randomUUID());
        Field stepField = ProcessInstance.class.getDeclaredField("stepStartedAt");
        stepField.setAccessible(true);
        stepField.set(inst, stepStartedAt);
        return inst;
    }

    @Test
    void detectsAndMarksStalledProcesses() throws Exception {
        ProcessInstance stale = runningInstance(LocalDateTime.now().minusMinutes(60));
        when(repository.findStalledProcesses(any(LocalDateTime.class))).thenReturn(List.of(stale));

        stepTimeoutService.detectStalledProcesses();

        assertThat(stale.getStatus()).isEqualTo(ProcessStatus.STALLED);
    }

    @Test
    void ignoresRecentProcesses() {
        when(repository.findStalledProcesses(any(LocalDateTime.class))).thenReturn(List.of());

        stepTimeoutService.detectStalledProcesses();

        verify(repository).findStalledProcesses(any(LocalDateTime.class));
        verifyNoMoreInteractions(repository);
    }
}
