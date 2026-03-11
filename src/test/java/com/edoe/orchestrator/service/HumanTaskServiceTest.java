package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.HumanTaskDefinition;
import com.edoe.orchestrator.dto.HumanTaskResponse;
import com.edoe.orchestrator.entity.HumanTask;
import com.edoe.orchestrator.entity.HumanTaskStatus;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.HumanTaskRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.lang.reflect.Field;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HumanTaskServiceTest {

    @Mock
    private HumanTaskRepository humanTaskRepository;

    @Mock
    private AuditLogService auditLogService;

    private HumanTaskService humanTaskService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        humanTaskService = new HumanTaskService(humanTaskRepository, auditLogService, objectMapper);
    }

    private ProcessInstance instance(UUID id) throws Exception {
        ProcessInstance inst = new ProcessInstance("TEST_FLOW", "REVIEW_STEP", "{}", ProcessStatus.SUSPENDED);
        Field f = ProcessInstance.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(inst, id);
        return inst;
    }

    private HumanTask pendingTask(UUID id, UUID processId) throws Exception {
        HumanTask task = new HumanTask(processId, "TEST_FLOW", "Manual Review", "APPROVAL_GRANTED",
                "{\"fields\":[]}", null);
        Field f = HumanTask.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(task, id);
        return task;
    }

    @Test
    void createTask_shouldPersistWithPendingStatus() throws Exception {
        UUID processId = UUID.randomUUID();
        ProcessInstance inst = instance(processId);
        HumanTaskDefinition def = new HumanTaskDefinition(
                "Manual Review", "APPROVAL_GRANTED",
                Map.of("fields", List.of()), null);

        when(humanTaskRepository.save(any())).thenAnswer(inv -> {
            HumanTask t = inv.getArgument(0);
            Field f = HumanTask.class.getDeclaredField("id");
            f.setAccessible(true);
            f.set(t, UUID.randomUUID());
            return t;
        });

        HumanTask task = humanTaskService.createTask(inst, def, null);

        assertThat(task.getStatus()).isEqualTo(HumanTaskStatus.PENDING);
        assertThat(task.getTaskName()).isEqualTo("Manual Review");
        assertThat(task.getSignalEvent()).isEqualTo("APPROVAL_GRANTED");
        assertThat(task.getProcessInstanceId()).isEqualTo(processId);
        ArgumentCaptor<HumanTask> captor = ArgumentCaptor.forClass(HumanTask.class);
        verify(humanTaskRepository).save(captor.capture());
        assertThat(captor.getValue().getStatus()).isEqualTo(HumanTaskStatus.PENDING);
    }

    @Test
    void completeTask_shouldMarkCompletedAndReturnEntity() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        HumanTask task = pendingTask(taskId, processId);

        when(humanTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(humanTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HumanTask result = humanTaskService.completeTask(taskId, Map.of("approved", true));

        assertThat(result.getStatus()).isEqualTo(HumanTaskStatus.COMPLETED);
        assertThat(result.getCompletedAt()).isNotNull();
        assertThat(result.getResultData()).contains("approved");
    }

    @Test
    void completeTask_shouldThrowWhenNotPending() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        HumanTask task = pendingTask(taskId, processId);
        task.setStatus(HumanTaskStatus.COMPLETED);

        when(humanTaskRepository.findById(taskId)).thenReturn(Optional.of(task));

        assertThatThrownBy(() -> humanTaskService.completeTask(taskId, Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot complete task");
    }

    @Test
    void cancelTask_shouldMarkCancelled() throws Exception {
        UUID taskId = UUID.randomUUID();
        UUID processId = UUID.randomUUID();
        HumanTask task = pendingTask(taskId, processId);

        when(humanTaskRepository.findById(taskId)).thenReturn(Optional.of(task));
        when(humanTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        HumanTaskResponse resp = humanTaskService.cancelTask(taskId);

        assertThat(resp.status()).isEqualTo(HumanTaskStatus.CANCELLED);
        assertThat(resp.completedAt()).isNotNull();
    }

    @Test
    void cancelTasksForProcess_shouldCancelAllPendingForProcess() throws Exception {
        UUID processId = UUID.randomUUID();
        UUID task1Id = UUID.randomUUID();
        UUID task2Id = UUID.randomUUID();
        HumanTask task1 = pendingTask(task1Id, processId);
        HumanTask task2 = pendingTask(task2Id, processId);

        when(humanTaskRepository.findByProcessInstanceId(processId))
                .thenReturn(List.of(task1, task2));
        when(humanTaskRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        humanTaskService.cancelTasksForProcess(processId);

        assertThat(task1.getStatus()).isEqualTo(HumanTaskStatus.CANCELLED);
        assertThat(task2.getStatus()).isEqualTo(HumanTaskStatus.CANCELLED);
        verify(humanTaskRepository, times(2)).save(any());
    }

    @Test
    void getTask_shouldThrow404WhenNotFound() {
        UUID taskId = UUID.randomUUID();
        when(humanTaskRepository.findById(taskId)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> humanTaskService.getTask(taskId))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(taskId.toString());
    }
}
