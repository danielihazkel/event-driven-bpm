package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.*;
import com.edoe.orchestrator.entity.ProcessDefinition;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.edoe.orchestrator.repository.ProcessDefinitionRepository;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.assertj.core.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class ManagementServiceTest {

    @Mock
    private ProcessDefinitionRepository definitionRepository;
    @Mock
    private ProcessInstanceRepository instanceRepository;
    @Mock
    private OutboxEventRepository outboxRepository;
    @Mock
    private TransitionService transitionService;

    private ManagementService managementService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TRANSITIONS_JSON = "{\"STEP_1_FINISHED\":[{\"next\":\"STEP_2\"}],\"STEP_2_FINISHED\":[{\"next\":\"COMPLETED\"}]}";

    @BeforeEach
    void setUp() {
        managementService = new ManagementService(
                definitionRepository, instanceRepository, outboxRepository, transitionService, objectMapper);
    }

    private ProcessDefinition definition(String name) {
        return new ProcessDefinition(name, "STEP_1", TRANSITIONS_JSON);
    }

    private ProcessInstance instance(UUID id, String definitionName, String step, ProcessStatus status)
            throws Exception {
        ProcessInstance inst = new ProcessInstance(definitionName, step, "{}", status);
        Field f = ProcessInstance.class.getDeclaredField("id");
        f.setAccessible(true);
        f.set(inst, id);
        return inst;
    }

    // --- Definition tests ---

    @Test
    void listDefinitions_returnsAll() {
        when(definitionRepository.findLatestVersionOfAll()).thenReturn(List.of(definition("FLOW_A"), definition("FLOW_B")));
        List<ProcessDefinitionResponse> result = managementService.listDefinitions();
        assertThat(result).hasSize(2);
        assertThat(result.get(0).name()).isEqualTo("FLOW_A");
    }

    @Test
    void createDefinition_savesAndReturns() {
        ProcessDefinitionRequest req = new ProcessDefinitionRequest("NEW_FLOW", "STEP_1",
                java.util.Map.of("STEP_1_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))), Map.of());
        when(definitionRepository.existsByName("NEW_FLOW")).thenReturn(false);
        when(definitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessDefinitionResponse resp = managementService.createDefinition(req);
        assertThat(resp.name()).isEqualTo("NEW_FLOW");
        assertThat(resp.initialStep()).isEqualTo("STEP_1");
    }

    @Test
    void createDefinition_throwsWhenAlreadyExists() {
        ProcessDefinitionRequest req = new ProcessDefinitionRequest("EXISTING", "STEP_1", java.util.Map.of(), Map.of());
        when(definitionRepository.existsByName("EXISTING")).thenReturn(true);

        assertThatThrownBy(() -> managementService.createDefinition(req))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("EXISTING");
    }

    @Test
    void getDefinition_returnsWhenFound() {
        when(definitionRepository.findTopByNameOrderByVersionDesc("MY_FLOW")).thenReturn(Optional.of(definition("MY_FLOW")));
        ProcessDefinitionResponse resp = managementService.getDefinition("MY_FLOW");
        assertThat(resp.name()).isEqualTo("MY_FLOW");
    }

    @Test
    void getDefinition_throwsWhenNotFound() {
        when(definitionRepository.findTopByNameOrderByVersionDesc("MISSING")).thenReturn(Optional.empty());
        assertThatThrownBy(() -> managementService.getDefinition("MISSING"))
                .isInstanceOf(NoSuchElementException.class);
    }

    @Test
    void updateDefinition_createsNewVersionRow() {
        ProcessDefinition existing = definition("MY_FLOW"); // version=1
        ProcessDefinitionRequest req = new ProcessDefinitionRequest("MY_FLOW", "STEP_A",
                java.util.Map.of("STEP_A_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))), Map.of());
        when(definitionRepository.findTopByNameOrderByVersionDesc("MY_FLOW")).thenReturn(Optional.of(existing));
        when(definitionRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessDefinitionResponse resp = managementService.updateDefinition("MY_FLOW", req);

        assertThat(resp.version()).isEqualTo(2);
        assertThat(resp.initialStep()).isEqualTo("STEP_A");
    }

    @Test
    void deleteDefinition_deletesWhenNoActiveProcesses() {
        when(definitionRepository.findTopByNameOrderByVersionDesc("FLOW_A")).thenReturn(Optional.of(definition("FLOW_A")));
        when(instanceRepository.existsByDefinitionNameAndStatusIn(eq("FLOW_A"), any())).thenReturn(false);

        managementService.deleteDefinition("FLOW_A");

        verify(definitionRepository).deleteAllByName("FLOW_A");
    }

    @Test
    void deleteDefinition_throwsWhenActiveProcessesExist() {
        when(definitionRepository.findTopByNameOrderByVersionDesc("BUSY_FLOW")).thenReturn(Optional.of(definition("BUSY_FLOW")));
        when(instanceRepository.existsByDefinitionNameAndStatusIn(eq("BUSY_FLOW"), any())).thenReturn(true);

        assertThatThrownBy(() -> managementService.deleteDefinition("BUSY_FLOW"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("BUSY_FLOW");
    }

    // --- Process instance tests ---

    @Test
    void listProcesses_noFilter_returnsAll() {
        Pageable pageable = PageRequest.of(0, 10);
        when(instanceRepository.findAll(pageable)).thenReturn(Page.empty());
        Page<ProcessInstanceResponse> page = managementService.listProcesses(null, null, pageable);
        assertThat(page).isEmpty();
    }

    @Test
    void cancelProcess_cancelsRunningProcess() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "FLOW", "STEP_1", ProcessStatus.RUNNING);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));
        when(instanceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessInstanceResponse resp = managementService.cancelProcess(id);

        assertThat(resp.status()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(resp.completedAt()).isNotNull();
    }

    @Test
    void cancelProcess_throwsWhenNotRunningOrStalled() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "FLOW", "COMPLETED", ProcessStatus.COMPLETED);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));

        assertThatThrownBy(() -> managementService.cancelProcess(id))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void retryProcess_retriesFailedProcess() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "FLOW", "STEP_1", ProcessStatus.FAILED);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));
        when(instanceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessInstanceResponse resp = managementService.retryProcess(id);

        assertThat(resp.status()).isEqualTo(ProcessStatus.RUNNING);
        verify(outboxRepository).save(any());
    }

    @Test
    void retryProcess_throwsWhenNotFailedOrStalled() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "FLOW", "STEP_1", ProcessStatus.RUNNING);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));

        assertThatThrownBy(() -> managementService.retryProcess(id))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void advanceProcess_callsHandleEvent() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "FLOW", "STEP_1", ProcessStatus.RUNNING);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));

        managementService.advanceProcess(id);

        verify(transitionService).handleEvent(eq(id.toString()), eq("STEP_1_FINISHED"), any());
    }

    @Test
    void advanceProcess_throwsWhenNotRunning() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "FLOW", "STEP_1", ProcessStatus.FAILED);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));

        assertThatThrownBy(() -> managementService.advanceProcess(id))
                .isInstanceOf(IllegalStateException.class);
    }

    // --- Signal tests ---

    @Test
    void signalProcess_delegatesToTransitionService() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "LOAN_FLOW", "DISBURSE_FUNDS", ProcessStatus.RUNNING);
        when(instanceRepository.existsById(id)).thenReturn(true);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));

        managementService.signalProcess(id, "APPROVAL_GRANTED", Map.of("approved", true));

        verify(transitionService).handleSignal(eq(id.toString()), eq("APPROVAL_GRANTED"),
                argThat(m -> Boolean.TRUE.equals(m.get("approved"))));
    }

    @Test
    void signalProcess_throwsWhenProcessNotFound() {
        UUID id = UUID.randomUUID();
        when(instanceRepository.existsById(id)).thenReturn(false);

        assertThatThrownBy(() -> managementService.signalProcess(id, "APPROVAL_GRANTED", Map.of()))
                .isInstanceOf(NoSuchElementException.class)
                .hasMessageContaining(id.toString());
    }

    @Test
    void cancelProcess_cancelsSuspendedProcess() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "LOAN_FLOW", "MANUAL_REVIEW", ProcessStatus.SUSPENDED);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));
        when(instanceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessInstanceResponse resp = managementService.cancelProcess(id);

        assertThat(resp.status()).isEqualTo(ProcessStatus.CANCELLED);
    }

    // --- Wake tests ---

    @Test
    void wakeProcess_scheduledProcess_setsRunningAndDispatchesOutbox() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "DELAY_FLOW", "PROCESS_REQUEST", ProcessStatus.SCHEDULED);
        inst.setWakeAt(LocalDateTime.now().plusMinutes(5));
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));
        when(instanceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        ProcessInstanceResponse resp = managementService.wakeProcess(id);

        assertThat(resp.status()).isEqualTo(ProcessStatus.RUNNING);
        assertThat(inst.getWakeAt()).isNull();
        verify(outboxRepository).save(any());
    }

    @Test
    void wakeProcess_nonScheduledProcess_throwsIllegalStateException() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instance(id, "DELAY_FLOW", "STEP_1", ProcessStatus.RUNNING);
        when(instanceRepository.findById(id)).thenReturn(Optional.of(inst));

        assertThatThrownBy(() -> managementService.wakeProcess(id))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("Cannot wake");
    }

    // --- Metrics tests ---

    @Test
    void getMetricsSummary_returnsCorrectCounts() {
        when(instanceRepository.countByStatus(ProcessStatus.RUNNING)).thenReturn(3L);
        when(instanceRepository.countByStatus(ProcessStatus.COMPLETED)).thenReturn(10L);
        when(instanceRepository.countByStatus(ProcessStatus.FAILED)).thenReturn(2L);
        when(instanceRepository.countByStatus(ProcessStatus.STALLED)).thenReturn(1L);
        when(instanceRepository.countByStatus(ProcessStatus.CANCELLED)).thenReturn(1L);
        when(instanceRepository.countByStatus(ProcessStatus.SCHEDULED)).thenReturn(0L);
        when(instanceRepository.countByStatus(ProcessStatus.WAITING_FOR_CHILD)).thenReturn(0L);

        MetricsSummaryResponse summary = managementService.getMetricsSummary();

        assertThat(summary.total()).isEqualTo(17L);
        assertThat(summary.running()).isEqualTo(3L);
        assertThat(summary.completed()).isEqualTo(10L);
        assertThat(summary.successRate()).isCloseTo(10.0 / 13.0, within(0.001));
    }

    @Test
    void getMetricsSummary_successRateZeroWhenNoDenominator() {
        when(instanceRepository.countByStatus(any())).thenReturn(0L);

        MetricsSummaryResponse summary = managementService.getMetricsSummary();

        assertThat(summary.successRate()).isEqualTo(0.0);
    }

    // --- Call Activity / Sub-Process tests ---

    @Test
    void cancelProcess_waitingForChild_cascadesToChildProcesses() throws Exception {
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        ProcessInstance parent = instance(parentId, "FLOW", "STEP_1", ProcessStatus.WAITING_FOR_CHILD);
        ProcessInstance child = instance(childId, "SUB_FLOW", "SUB_STEP", ProcessStatus.RUNNING);
        child.setParentProcessId(parentId);

        when(instanceRepository.findById(parentId)).thenReturn(Optional.of(parent));
        when(instanceRepository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(instanceRepository.findByParentProcessId(parentId)).thenReturn(List.of(child));

        ProcessInstanceResponse resp = managementService.cancelProcess(parentId);

        assertThat(resp.status()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(child.getStatus()).isEqualTo(ProcessStatus.CANCELLED);
        assertThat(child.getCompletedAt()).isNotNull();

        // Parent + child both saved
        verify(instanceRepository, times(2)).saveAndFlush(any());
    }

    @Test
    void getMetricsSummary_includesWaitingForChildCount() {
        when(instanceRepository.countByStatus(ProcessStatus.RUNNING)).thenReturn(1L);
        when(instanceRepository.countByStatus(ProcessStatus.COMPLETED)).thenReturn(5L);
        when(instanceRepository.countByStatus(ProcessStatus.FAILED)).thenReturn(0L);
        when(instanceRepository.countByStatus(ProcessStatus.STALLED)).thenReturn(0L);
        when(instanceRepository.countByStatus(ProcessStatus.CANCELLED)).thenReturn(0L);
        when(instanceRepository.countByStatus(ProcessStatus.SCHEDULED)).thenReturn(0L);
        when(instanceRepository.countByStatus(ProcessStatus.WAITING_FOR_CHILD)).thenReturn(2L);

        MetricsSummaryResponse summary = managementService.getMetricsSummary();

        assertThat(summary.waitingForChild()).isEqualTo(2L);
        assertThat(summary.total()).isEqualTo(8L);
    }
}
