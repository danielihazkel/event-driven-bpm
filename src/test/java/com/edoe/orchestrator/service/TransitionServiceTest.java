package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.OutboxEvent;
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

import com.edoe.orchestrator.dto.TransitionRule;

import java.lang.reflect.Field;
import java.time.LocalDateTime;
import java.util.List;
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

    @Mock
    private ProcessDefinitionRepository definitionRepository;

    private TransitionService transitionService;
    private final ObjectMapper objectMapper = new ObjectMapper();

    private static final String TRANSITIONS_JSON = "{\"STEP_1_FINISHED\":[{\"next\":\"STEP_2\"}],\"STEP_2_FINISHED\":[{\"next\":\"COMPLETED\"}]}";

    @BeforeEach
    void setUp() {
        transitionService = new TransitionService(repository, outboxRepository, definitionRepository, objectMapper);
    }

    private ProcessDefinition definition(String name) {
        return new ProcessDefinition(name, "STEP_1", TRANSITIONS_JSON);
    }

    private ProcessInstance instanceWithId(UUID id, String step, ProcessStatus status, String contextJson)
            throws Exception {
        return instanceWithIdAndDef(id, "TEST_FLOW", step, status, contextJson);
    }

    private ProcessInstance instanceWithIdAndDef(UUID id, String defName, String step, ProcessStatus status,
            String contextJson) throws Exception {
        ProcessInstance inst = new ProcessInstance(defName, step, contextJson, status);
        Field field = ProcessInstance.class.getDeclaredField("id");
        field.setAccessible(true);
        field.set(inst, id);
        return inst;
    }

    // 1. startProcess saves with correct initial state and saves OutboxEvent for
    // STEP_1
    @Test
    void startProcess_savesInstanceAndCreatesOutboxEvent() {
        UUID id = UUID.randomUUID();
        when(definitionRepository.findByName("USER_REGISTRATION"))
                .thenReturn(Optional.of(definition("USER_REGISTRATION")));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            ProcessInstance pi = inv.getArgument(0);
            try {
                Field f = ProcessInstance.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(pi, id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
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

    // 2. handleEvent STEP_1_FINISHED → transitions to STEP_2, saves OutboxEvent for
    // STEP_2
    @Test
    void handleEvent_step1Finished_transitionsToStep2() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "STEP_1", ProcessStatus.RUNNING, "{\"userId\":\"user-42\"}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(definition("TEST_FLOW")));

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
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(definition("TEST_FLOW")));

        transitionService.handleEvent(id.toString(), "STEP_2_FINISHED", Map.of("finalResult", "done"));

        assertThat(inst.getCurrentStep()).isEqualTo("COMPLETED");
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
        verify(repository).saveAndFlush(inst);
        verifyNoInteractions(outboxRepository);
    }

    // 4. handleEvent duplicate — stale STEP_1_FINISHED when already on STEP_2 →
    // silently ignored
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
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(definition("TEST_FLOW")));

        transitionService.handleEvent(id.toString(), "STEP_1_FINISHED", Map.of("result", "ok"));

        String mergedJson = inst.getContextData();
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(mergedJson, Map.class);
        assertThat(merged).containsEntry("userId", "user-42");
        assertThat(merged).containsEntry("result", "ok");
    }

    // 8. startProcess uses definition's initialStep
    @Test
    void startProcess_usesDefinitionInitialStep() {
        UUID id = UUID.randomUUID();
        ProcessDefinition def = new ProcessDefinition("CUSTOM_FLOW", "INIT_STEP",
                "{\"INIT_STEP_FINISHED\":[{\"next\":\"COMPLETED\"}]}");
        when(definitionRepository.findByName("CUSTOM_FLOW")).thenReturn(Optional.of(def));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> {
            ProcessInstance pi = inv.getArgument(0);
            try {
                Field f = ProcessInstance.class.getDeclaredField("id");
                f.setAccessible(true);
                f.set(pi, id);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
            return pi;
        });

        transitionService.startProcess("CUSTOM_FLOW", Map.of());

        ArgumentCaptor<ProcessInstance> captor = ArgumentCaptor.forClass(ProcessInstance.class);
        verify(repository).saveAndFlush(captor.capture());
        assertThat(captor.getValue().getCurrentStep()).isEqualTo("INIT_STEP");

        ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(outboxCaptor.capture());
        assertThat(outboxCaptor.getValue().getEventType()).isEqualTo("INIT_STEP");
    }

    // 9. startProcess throws when definition not found
    @Test
    void startProcess_throwsWhenDefinitionNotFound() {
        when(definitionRepository.findByName("MISSING")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> transitionService.startProcess("MISSING", Map.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("MISSING");
    }

    // 10. handleEvent on last step sets completedAt
    @Test
    void handleEvent_completedProcess_setsCompletedAt() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "STEP_2", ProcessStatus.RUNNING, "{}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(definition("TEST_FLOW")));

        transitionService.handleEvent(id.toString(), "STEP_2_FINISHED", Map.of());

        assertThat(inst.getCompletedAt()).isNotNull();
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.COMPLETED);
    }

    // 11. handleEvent with a matching conditional branch routes to that branch's
    // next step
    @Test
    void handleEvent_conditionalBranch_routesToMatchingNext() throws Exception {
        UUID id = UUID.randomUUID();
        String condTransitions = "{\"STEP_1_FINISHED\":["
                + "{\"condition\":\"#approved == true\",\"next\":\"APPROVE_STEP\"},"
                + "{\"next\":\"REJECT_STEP\"}"
                + "]}";
        ProcessDefinition def = new ProcessDefinition("TEST_FLOW", "STEP_1", condTransitions);
        // context_data already has approved=true
        ProcessInstance inst = instanceWithId(id, "STEP_1", ProcessStatus.RUNNING, "{\"approved\":true}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "STEP_1_FINISHED", Map.of());

        assertThat(inst.getCurrentStep()).isEqualTo("APPROVE_STEP");
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    // 12. handleEvent with unmatched conditional branch falls through to
    // unconditional default
    @Test
    void handleEvent_conditionalBranch_fallsThroughToDefault() throws Exception {
        UUID id = UUID.randomUUID();
        String condTransitions = "{\"STEP_1_FINISHED\":["
                + "{\"condition\":\"#approved == true\",\"next\":\"APPROVE_STEP\"},"
                + "{\"next\":\"REJECT_STEP\"}"
                + "]}";
        ProcessDefinition def = new ProcessDefinition("TEST_FLOW", "STEP_1", condTransitions);
        // context_data has approved=false — first branch won't match, falls to default
        ProcessInstance inst = instanceWithId(id, "STEP_1", ProcessStatus.RUNNING, "{\"approved\":false}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "STEP_1_FINISHED", Map.of());

        assertThat(inst.getCurrentStep()).isEqualTo("REJECT_STEP");
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.RUNNING);
    }

    // 13. Fork rule fans out — sets PARALLEL_WAIT, saves N outbox events, sets
    // parallelPending
    @Test
    void handleEvent_forkRule_setsParallelWaitAndDispatchesBranches() throws Exception {
        UUID id = UUID.randomUUID();
        String forkTransitions = "{\"PREPARE_FINISHED\":["
                + "{\"parallel\":[\"VALIDATE_CREDIT\",\"VERIFY_IDENTITY\"],\"joinStep\":\"APPROVE_LOAN\"}"
                + "]}";
        ProcessDefinition def = new ProcessDefinition("TEST_FLOW", "PREPARE", forkTransitions);
        ProcessInstance inst = instanceWithId(id, "PREPARE", ProcessStatus.RUNNING, "{}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "PREPARE_FINISHED", Map.of());

        assertThat(inst.getCurrentStep()).isEqualTo("PARALLEL_WAIT");
        assertThat(inst.getParallelPending()).isEqualTo(2);
        assertThat(inst.getJoinStep()).isEqualTo("APPROVE_LOAN");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository, times(2)).save(captor.capture());
        List<String> dispatchedTypes = captor.getAllValues().stream()
                .map(OutboxEvent::getEventType).toList();
        assertThat(dispatchedTypes).containsExactlyInAnyOrder("VALIDATE_CREDIT", "VERIFY_IDENTITY");
    }

    // 14. First parallel branch completes — decrements pending, stays in
    // PARALLEL_WAIT, no outbox
    @Test
    void handleEvent_firstParallelBranch_decrementsAndWaits() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "PARALLEL_WAIT", ProcessStatus.RUNNING, "{}");
        inst.setParallelPending(2);
        inst.setJoinStep("APPROVE_LOAN");
        inst.setParallelCompleted("[]");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        transitionService.handleEvent(id.toString(), "VALIDATE_CREDIT_FINISHED", Map.of("creditScore", 750));

        assertThat(inst.getParallelPending()).isEqualTo(1);
        assertThat(inst.getCurrentStep()).isEqualTo("PARALLEL_WAIT");
        verifyNoInteractions(outboxRepository);
    }

    // 15. Last parallel branch completes — transitions to joinStep, dispatches join
    // outbox event
    @Test
    void handleEvent_lastParallelBranch_transitionsToJoinStep() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "PARALLEL_WAIT", ProcessStatus.RUNNING, "{\"creditScore\":750}");
        inst.setParallelPending(1);
        inst.setJoinStep("APPROVE_LOAN");
        inst.setParallelCompleted("[\"VALIDATE_CREDIT_FINISHED\"]");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));

        transitionService.handleEvent(id.toString(), "VERIFY_IDENTITY_FINISHED", Map.of("identityVerified", true));

        assertThat(inst.getCurrentStep()).isEqualTo("APPROVE_LOAN");
        assertThat(inst.getParallelPending()).isNull();
        assertThat(inst.getJoinStep()).isNull();
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.RUNNING);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("APPROVE_LOAN");
    }

    // 16. Duplicate parallel branch event is ignored (idempotency during fork)
    @Test
    void handleEvent_duplicateParallelBranch_ignored() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "PARALLEL_WAIT", ProcessStatus.RUNNING, "{}");
        inst.setParallelPending(1);
        inst.setJoinStep("APPROVE_LOAN");
        // VALIDATE_CREDIT_FINISHED already recorded as completed
        inst.setParallelCompleted("[\"VALIDATE_CREDIT_FINISHED\"]");
        when(repository.findById(id)).thenReturn(Optional.of(inst));

        transitionService.handleEvent(id.toString(), "VALIDATE_CREDIT_FINISHED", Map.of("creditScore", 800));

        // Nothing should change — no save, no outbox
        verify(repository, never()).saveAndFlush(any());
        verifyNoInteractions(outboxRepository);
        assertThat(inst.getParallelPending()).isEqualTo(1);
    }

    // 17. suspend rule sets SUSPENDED, advances currentStep, writes no outbox event
    @Test
    void handleEvent_suspendRule_setsStatusSuspendedAndNoOutbox() throws Exception {
        UUID id = UUID.randomUUID();
        // Transition: VALIDATE_CREDIT_FINISHED → suspend at MANUAL_REVIEW when
        // creditScore <= 700
        String suspendTransitions = "{\"VALIDATE_CREDIT_FINISHED\":["
                + "{\"condition\":\"#creditScore > 700\",\"next\":\"AUTO_APPROVE\"},"
                + "{\"next\":\"MANUAL_REVIEW\",\"suspend\":true}"
                + "]}";
        ProcessDefinition def = new ProcessDefinition("TEST_FLOW", "VALIDATE_CREDIT", suspendTransitions);
        ProcessInstance inst = instanceWithId(id, "VALIDATE_CREDIT", ProcessStatus.RUNNING, "{\"creditScore\":600}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "VALIDATE_CREDIT_FINISHED", Map.of());

        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.SUSPENDED);
        assertThat(inst.getCurrentStep()).isEqualTo("MANUAL_REVIEW");
        verifyNoInteractions(outboxRepository);
    }

    // 18. handleSignal on a SUSPENDED process resumes and dispatches the next step
    @Test
    void handleSignal_suspendedProcess_resumesAndDispatchesNextStep() throws Exception {
        UUID id = UUID.randomUUID();
        // Transitions keyed on signal event name (not _FINISHED convention)
        String signalTransitions = "{\"APPROVAL_GRANTED\":["
                + "{\"condition\":\"#approved == true\",\"next\":\"DISBURSE_FUNDS\"},"
                + "{\"next\":\"SEND_REJECTION\"}"
                + "]}";
        ProcessDefinition def = new ProcessDefinition("TEST_FLOW", "VALIDATE_CREDIT", signalTransitions);
        ProcessInstance inst = instanceWithId(id, "MANUAL_REVIEW", ProcessStatus.SUSPENDED, "{\"creditScore\":600}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(def));

        transitionService.handleSignal(id.toString(), "APPROVAL_GRANTED", Map.of("approved", true));

        assertThat(inst.getCurrentStep()).isEqualTo("DISBURSE_FUNDS");
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.RUNNING);

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("DISBURSE_FUNDS");
    }

    // 19. handleSignal on a non-SUSPENDED process throws IllegalStateException
    @Test
    void handleSignal_nonSuspendedProcess_throwsIllegalStateException() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessInstance inst = instanceWithId(id, "STEP_1", ProcessStatus.RUNNING, "{}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));

        assertThatThrownBy(() -> transitionService.handleSignal(id.toString(), "SOME_SIGNAL", Map.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(id.toString());
    }

    // 20. handleSignal merges signal data into existing context before evaluating
    // conditions
    @Test
    void handleSignal_mergesSignalDataIntoContext() throws Exception {
        UUID id = UUID.randomUUID();
        String signalTransitions = "{\"APPROVAL_GRANTED\":["
                + "{\"condition\":\"#approved == true\",\"next\":\"DISBURSE_FUNDS\"},"
                + "{\"next\":\"SEND_REJECTION\"}"
                + "]}";
        ProcessDefinition def = new ProcessDefinition("TEST_FLOW", "VALIDATE_CREDIT", signalTransitions);
        // Existing context has loanAmount; signal will add approved
        ProcessInstance inst = instanceWithId(id, "MANUAL_REVIEW", ProcessStatus.SUSPENDED, "{\"loanAmount\":50000}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(def));

        transitionService.handleSignal(id.toString(), "APPROVAL_GRANTED", Map.of("approved", false));

        // Rejected path (approved=false → SEND_REJECTION)
        assertThat(inst.getCurrentStep()).isEqualTo("SEND_REJECTION");

        // Both original and signal data must be in the merged context
        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(inst.getContextData(), Map.class);
        assertThat(merged).containsEntry("loanAmount", 50000);
        assertThat(merged).containsEntry("approved", false);
    }

    // 21. handleEvent <step>_FAILED initiates compensation
    @Test
    void handleEvent_stepFailed_initiatesCompensation() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessDefinition def = new ProcessDefinition("PAYMENT_SAGA", "RESERVE_INVENTORY", "{}");
        def.setCompensationsJson("{\"RESERVE_INVENTORY\":\"UNDO_RESERVE_INVENTORY\"}");

        // Instance is at CHARGE_PAYMENT, having already completed RESERVE_INVENTORY
        ProcessInstance inst = instanceWithIdAndDef(id, "PAYMENT_SAGA", "CHARGE_PAYMENT", ProcessStatus.RUNNING, "{}");
        inst.setCompletedSteps("[\"RESERVE_INVENTORY\"]");

        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("PAYMENT_SAGA")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "CHARGE_PAYMENT_FAILED", Map.of("error", "payment declined"));

        assertThat(inst.getCompensating()).isTrue();
        assertThat(inst.getCurrentStep()).isEqualTo("UNDO_RESERVE_INVENTORY");
        // The completed steps should have popped RESERVE_INVENTORY
        assertThat(inst.getCompletedSteps()).isEqualTo("[]");

        ArgumentCaptor<OutboxEvent> captor = ArgumentCaptor.forClass(OutboxEvent.class);
        verify(outboxRepository).save(captor.capture());
        assertThat(captor.getValue().getEventType()).isEqualTo("UNDO_RESERVE_INVENTORY");

        @SuppressWarnings("unchecked")
        Map<String, Object> merged = objectMapper.readValue(inst.getContextData(), Map.class);
        assertThat(merged).containsEntry("error", "payment declined");
    }

    // 22. handleEvent compensation finish transitions to next compensation or fails
    // process
    @Test
    void handleEvent_compensationFinished_completesRollbackAndFailsProcess() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessDefinition def = new ProcessDefinition("PAYMENT_SAGA", "RESERVE_INVENTORY", "{}");
        def.setCompensationsJson("{\"RESERVE_INVENTORY\":\"UNDO_RESERVE_INVENTORY\"}");

        // Instance is currently compensating UNDO_RESERVE_INVENTORY, and completedSteps
        // is empty
        ProcessInstance inst = instanceWithIdAndDef(id, "PAYMENT_SAGA", "UNDO_RESERVE_INVENTORY", ProcessStatus.RUNNING,
                "{}");
        inst.setCompensating(true);
        inst.setCompletedSteps("[]"); // no more steps to pop

        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("PAYMENT_SAGA")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "UNDO_RESERVE_INVENTORY_FINISHED", Map.of());

        // Process should become FAILED
        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.FAILED);
        assertThat(inst.getCompletedAt()).isNotNull();

        // no outbox events should be dispatched for a terminal state
        verifyNoInteractions(outboxRepository);
    }

    // 23. delay rule sets SCHEDULED status, wakeAt, advances currentStep, writes no outbox event
    @Test
    void handleEvent_delayRule_setsStatusScheduledAndNoOutbox() throws Exception {
        UUID id = UUID.randomUUID();
        String delayTransitions = "{\"PREPARE_REQUEST_FINISHED\":["
                + "{\"delayMs\":3000,\"next\":\"PROCESS_REQUEST\"}"
                + "]}";
        ProcessDefinition def = new ProcessDefinition("TEST_FLOW", "PREPARE_REQUEST", delayTransitions);
        ProcessInstance inst = instanceWithId(id, "PREPARE_REQUEST", ProcessStatus.RUNNING, "{}");
        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("TEST_FLOW")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "PREPARE_REQUEST_FINISHED", Map.of());

        assertThat(inst.getStatus()).isEqualTo(ProcessStatus.SCHEDULED);
        assertThat(inst.getCurrentStep()).isEqualTo("PROCESS_REQUEST");
        assertThat(inst.getWakeAt()).isNotNull();
        assertThat(inst.getWakeAt()).isAfter(LocalDateTime.now().minusSeconds(1));
        verifyNoInteractions(outboxRepository);
    }

    // 24. step successfully completes and is added to completedSteps
    @Test
    void handleEvent_normalCompletion_addsToCompletedSteps() throws Exception {
        UUID id = UUID.randomUUID();
        ProcessDefinition def = new ProcessDefinition("PAYMENT_SAGA", "RESERVE_INVENTORY",
                "{\"RESERVE_INVENTORY_FINISHED\":[{\"next\":\"CHARGE_PAYMENT\"}]}");
        def.setCompensationsJson("{}");

        ProcessInstance inst = instanceWithIdAndDef(id, "PAYMENT_SAGA", "RESERVE_INVENTORY", ProcessStatus.RUNNING,
                "{}");
        inst.setCompletedSteps("[]");

        when(repository.findById(id)).thenReturn(Optional.of(inst));
        when(repository.saveAndFlush(any())).thenAnswer(inv -> inv.getArgument(0));
        when(definitionRepository.findByName("PAYMENT_SAGA")).thenReturn(Optional.of(def));

        transitionService.handleEvent(id.toString(), "RESERVE_INVENTORY_FINISHED", Map.of());

        assertThat(inst.getCompletedSteps()).isEqualTo("[\"RESERVE_INVENTORY\"]");
        assertThat(inst.getCurrentStep()).isEqualTo("CHARGE_PAYMENT");
    }
}
