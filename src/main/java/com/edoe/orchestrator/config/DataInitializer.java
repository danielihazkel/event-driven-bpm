package com.edoe.orchestrator.config;

import com.edoe.orchestrator.dto.HttpRequestConfig;
import com.edoe.orchestrator.dto.HumanTaskDefinition;
import com.edoe.orchestrator.dto.TransitionRule;
import com.edoe.orchestrator.entity.ProcessDefinition;
import com.edoe.orchestrator.repository.ProcessDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Seeds example process definitions on every startup using upsert semantics.
 * If a definition already exists it is updated in place; otherwise it is
 * created.
 * This ensures the example flows always reflect the latest engine features
 * after a restart.
 *
 * When a new engine feature is added, update the relevant flow (or add a new
 * one) here.
 */
@Slf4j
@RequiredArgsConstructor
@Component
public class DataInitializer implements CommandLineRunner {

    private final ProcessDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) {
        seedDefaultFlow();
        seedLoanApproval();
        seedOrderFulfillment();
        seedParallelFlow();
        seedPaymentSaga();
        seedDelayFlow();
        seedCreditCheckSub();
        seedSubProcessFlow();
        seedScatterGatherFlow();
        seedHttpStepFlow();
        seedHumanTaskFlow();
    }

    // -------------------------------------------------------------------------
    // DEFAULT_FLOW — simple linear two-step sequence
    // Feature: basic step chaining
    // -------------------------------------------------------------------------
    private void seedDefaultFlow() {
        upsert("DEFAULT_FLOW", "STEP_1", Map.of(
                "STEP_1_FINISHED", List.of(TransitionRule.of(null, "STEP_2")),
                "STEP_2_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // LOAN_APPROVAL — credit-score based approval workflow
    // Features: conditional branching (SpEL), human task gate (Phase 13),
    //           output mapping (Phase 11)
    //
    // Happy path (creditScore > 700):
    // VALIDATE_CREDIT → AUTO_APPROVE → DISBURSE_FUNDS → COMPLETED
    // Manual review path (creditScore <= 700):
    // VALIDATE_CREDIT → MANUAL_REVIEW (SUSPENDED — HumanTask created, awaits completion)
    // POST /api/tasks/{taskId}/complete
    // {"resultData":{"approved":true,"notes":"Looks good"}}
    // → approved=true → DISBURSE_FUNDS → COMPLETED
    // → approved=false → SEND_REJECTION → COMPLETED
    // -------------------------------------------------------------------------
    private void seedLoanApproval() {
        upsert("LOAN_APPROVAL", "VALIDATE_CREDIT", Map.of(
                "VALIDATE_CREDIT_FINISHED", List.of(
                        // ofMapped: only creditScore + creditBureau are persisted to context;
                        // other worker output fields (e.g. rawFlags, reportId) are discarded
                        TransitionRule.ofMapped("#creditScore > 700", "AUTO_APPROVE",
                                Map.of("creditScore",  "$.creditScore",
                                       "creditBureau", "$.meta.bureau")),
                        // humanTask: creates a HumanTask record for the loan-officer frontend
                        TransitionRule.humanTask(null, new HumanTaskDefinition(
                                "Manual Loan Review",
                                "APPROVAL_GRANTED",
                                Map.of("fields", List.of(
                                        Map.of("name", "approved", "type", "boolean", "label", "Approve?"),
                                        Map.of("name", "notes",    "type", "string",  "label", "Notes")
                                )),
                                null // no assignee filter
                        ), "MANUAL_REVIEW")
                ),
                "AUTO_APPROVE_FINISHED", List.of(
                        TransitionRule.of(null, "DISBURSE_FUNDS")),
                // Signal key — matches HumanTaskDefinition.signalEvent above
                "APPROVAL_GRANTED", List.of(
                        TransitionRule.of("#approved == true", "DISBURSE_FUNDS"),
                        TransitionRule.of(null, "SEND_REJECTION") // default branch
                ),
                "DISBURSE_FUNDS_FINISHED", List.of(TransitionRule.of(null, "COMPLETED")),
                "SEND_REJECTION_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // ORDER_FULFILLMENT — e-commerce order processing
    // Features: longer chain, conditional branching at two decision points,
    // multiple terminal paths all converging to COMPLETED
    //
    // Happy path: {"inventoryAvailable": true, "paymentSuccess": true}
    // VALIDATE_ORDER → RESERVE_INVENTORY → PROCESS_PAYMENT → SHIP_ORDER → COMPLETED
    // Out-of-stock: {"inventoryAvailable": false}
    // VALIDATE_ORDER → RESERVE_INVENTORY → NOTIFY_OUT_OF_STOCK → COMPLETED
    // Payment failed: {"inventoryAvailable": true, "paymentSuccess": false}
    // VALIDATE_ORDER → RESERVE_INVENTORY → PROCESS_PAYMENT → NOTIFY_PAYMENT_FAILED
    // → COMPLETED
    // -------------------------------------------------------------------------
    private void seedOrderFulfillment() {
        upsert("ORDER_FULFILLMENT", "VALIDATE_ORDER", Map.of(
                "VALIDATE_ORDER_FINISHED", List.of(
                        TransitionRule.of(null, "RESERVE_INVENTORY")),
                "RESERVE_INVENTORY_FINISHED", List.of(
                        TransitionRule.of("#inventoryAvailable == true", "PROCESS_PAYMENT"),
                        TransitionRule.of(null, "NOTIFY_OUT_OF_STOCK") // default branch
                ),
                "PROCESS_PAYMENT_FINISHED", List.of(
                        TransitionRule.of("#paymentSuccess == true", "SHIP_ORDER"),
                        TransitionRule.of(null, "NOTIFY_PAYMENT_FAILED") // default branch
                ),
                "SHIP_ORDER_FINISHED", List.of(TransitionRule.of(null, "COMPLETED")),
                "NOTIFY_OUT_OF_STOCK_FINISHED", List.of(TransitionRule.of(null, "COMPLETED")),
                "NOTIFY_PAYMENT_FAILED_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // PARALLEL_FLOW — demonstrates parallel fork / join (Phase 7)
    // Features: fan-out to multiple steps, wait for all to complete, then join
    //
    // Flow:
    // PREPARE_APPLICATION → [VALIDATE_CREDIT ∥ VERIFY_IDENTITY] → APPROVE_LOAN →
    // COMPLETED
    // -------------------------------------------------------------------------
    private void seedParallelFlow() {
        upsert("PARALLEL_FLOW", "PREPARE_APPLICATION", Map.of(
                "PREPARE_APPLICATION_FINISHED", List.of(
                        TransitionRule.fork(
                                List.of("VALIDATE_CREDIT", "VERIFY_IDENTITY"),
                                "APPROVE_LOAN")),
                "APPROVE_LOAN_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // PAYMENT_SAGA — demonstrates compensation/rollback (Phase 9)
    // Features: saga pattern execution on failure
    //
    // Flow:
    // RESERVE_INVENTORY → CHARGE_PAYMENT → SHIP_ORDER → COMPLETED
    // Compensations:
    // RESERVE_INVENTORY → UNDO_RESERVE_INVENTORY
    // CHARGE_PAYMENT → REFUND_PAYMENT
    // -------------------------------------------------------------------------
    private void seedPaymentSaga() {
        upsert("PAYMENT_SAGA", "RESERVE_INVENTORY", Map.of(
                "RESERVE_INVENTORY_FINISHED", List.of(TransitionRule.of(null, "CHARGE_PAYMENT")),
                "CHARGE_PAYMENT_FINISHED", List.of(TransitionRule.of(null, "SHIP_ORDER")),
                "SHIP_ORDER_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))),
                Map.of(
                        "RESERVE_INVENTORY", "UNDO_RESERVE_INVENTORY",
                        "CHARGE_PAYMENT", "REFUND_PAYMENT"));
    }

    // -------------------------------------------------------------------------
    // DELAY_FLOW — demonstrates timer / delay steps (Phase 10)
    // Features: delayMs rule parks process as SCHEDULED; TimerService wakes it
    //
    // Flow:
    // PREPARE_REQUEST → (3 000 ms delay) → PROCESS_REQUEST → COMPLETED
    // -------------------------------------------------------------------------
    private void seedDelayFlow() {
        upsert("DELAY_FLOW", "PREPARE_REQUEST", Map.of(
                "PREPARE_REQUEST_FINISHED", List.of(TransitionRule.delay(3000L, "PROCESS_REQUEST")),
                "PROCESS_REQUEST_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // CREDIT_CHECK_SUB — child definition used by SUB_PROCESS_FLOW (Phase 10)
    // Features: standalone sub-process invoked as a call activity
    //
    // Flow:
    // FETCH_CREDIT_REPORT → EVALUATE_SCORE → COMPLETED
    // -------------------------------------------------------------------------
    private void seedCreditCheckSub() {
        upsert("CREDIT_CHECK_SUB", "FETCH_CREDIT_REPORT", Map.of(
                "FETCH_CREDIT_REPORT_FINISHED", List.of(TransitionRule.of(null, "EVALUATE_SCORE")),
                "EVALUATE_SCORE_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // SUB_PROCESS_FLOW — demonstrates sub-process / call activity (Phase 10)
    // Features: callActivity rule spawns CREDIT_CHECK_SUB, parent waits
    //
    // Flow:
    // COLLECT_APPLICATION → callActivity(CREDIT_CHECK_SUB) → MAKE_DECISION → COMPLETED
    // -------------------------------------------------------------------------
    private void seedSubProcessFlow() {
        upsert("SUB_PROCESS_FLOW", "COLLECT_APPLICATION", Map.of(
                "COLLECT_APPLICATION_FINISHED", List.of(
                        TransitionRule.callActivity(null, "CREDIT_CHECK_SUB", "MAKE_DECISION")),
                "MAKE_DECISION_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // SCATTER_GATHER_FLOW — demonstrates multi-instance scatter-gather (Phase 10)
    // Features: multiInstanceVariable reads a List from context, dispatches one
    // PROCESS_ORDER__MI__N command per element, gathers results into
    // multiInstanceResults, then advances to SHIP_ORDERS.
    //
    // Start payload example:
    // {"definitionName":"SCATTER_GATHER_FLOW","initialData":{
    //   "orderItems":[{"sku":"WIDGET-A","qty":2},{"sku":"WIDGET-B","qty":1}]}}
    //
    // Flow:
    // RECEIVE_ORDERS → scatter(orderItems → PROCESS_ORDER) → SHIP_ORDERS → COMPLETED
    // -------------------------------------------------------------------------
    private void seedScatterGatherFlow() {
        upsert("SCATTER_GATHER_FLOW", "RECEIVE_ORDERS", Map.of(
                "RECEIVE_ORDERS_FINISHED", List.of(
                        TransitionRule.multiInstance("orderItems", "PROCESS_ORDER", "SHIP_ORDERS")),
                "SHIP_ORDERS_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // HTTP_STEP_FLOW — demonstrates native HTTP step execution (Phase 12)
    // Feature: httpRequest in TransitionRule bypasses Kafka; calls an external
    // HTTP endpoint inline and merges the JSON response into context_data.
    //
    // After STEP_1 finishes (handled by the built-in StepOneWorkerTask), the
    // engine calls https://jsonplaceholder.typicode.com/todos/1 (a free public
    // test API) and merges the JSON response into context_data. The process then
    // advances to STEP_2 via the normal Kafka/Outbox path and completes.
    //
    // Start with: {"definitionName":"HTTP_STEP_FLOW","initialData":{}}
    //
    // Flow:
    // STEP_1 → (HTTP GET jsonplaceholder.typicode.com/todos/1) → STEP_2 → COMPLETED
    // -------------------------------------------------------------------------
    private void seedHttpStepFlow() {
        upsert("HTTP_STEP_FLOW", "STEP_1", Map.of(
                "STEP_1_FINISHED", List.of(
                        TransitionRule.httpStep(null,
                                new HttpRequestConfig(
                                        "https://jsonplaceholder.typicode.com/todos/1",
                                        "GET",
                                        Map.of("Accept", "application/json"),
                                        null),
                                "STEP_2")),
                "STEP_2_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // HUMAN_TASK_FLOW — simple 3-step flow demonstrating the human task gate
    // Feature: humanTask rule suspends process and creates HumanTask record
    //
    // Flow:
    // SUBMIT → REVIEW_TASK (SUSPENDED — HumanTask created) → COMPLETE → COMPLETED
    //
    // Start: {"definitionName":"HUMAN_TASK_FLOW","initialData":{"submitter":"alice"}}
    // Complete: POST /api/tasks/{taskId}/complete
    //           {"resultData":{"approved":true,"comment":"LGTM"}}
    // -------------------------------------------------------------------------
    private void seedHumanTaskFlow() {
        upsert("HUMAN_TASK_FLOW", "SUBMIT", Map.of(
                "SUBMIT_FINISHED", List.of(
                        TransitionRule.humanTask(null, new HumanTaskDefinition(
                                "Review Submission",
                                "REVIEW_APPROVED",
                                Map.of("fields", List.of(
                                        Map.of("name", "approved", "type", "boolean", "label", "Approve?"),
                                        Map.of("name", "comment",  "type", "string",  "label", "Comment")
                                )),
                                null
                        ), "REVIEW_TASK")),
                "REVIEW_APPROVED", List.of(
                        TransitionRule.of(null, "COMPLETE")),
                "COMPLETE_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void upsert(String name, String initialStep, Map<String, List<TransitionRule>> transitions) {
        upsert(name, initialStep, transitions, Map.of());
    }

    private void upsert(String name, String initialStep, Map<String, List<TransitionRule>> transitions,
            Map<String, String> compensations) {
        String transitionsJson = serialize(transitions);
        String compensationsJson = serializeCompensations(compensations);
        Optional<ProcessDefinition> existing = definitionRepository.findTopByNameOrderByVersionDesc(name);
        if (existing.isPresent()) {
            ProcessDefinition def = existing.get();
            def.setInitialStep(initialStep);
            def.setTransitionsJson(transitionsJson);
            def.setCompensationsJson(compensationsJson);
            def.setUpdatedAt(LocalDateTime.now());
            definitionRepository.save(def);
            log.info("Updated example flow: {}", name);
        } else {
            definitionRepository.save(new ProcessDefinition(name, initialStep, transitionsJson, compensationsJson));
            log.info("Seeded example flow: {}", name);
        }
    }

    private String serialize(Map<String, List<TransitionRule>> transitions) {
        try {
            return objectMapper.writeValueAsString(transitions);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize transitions for example flow seeding", e);
        }
    }

    private String serializeCompensations(Map<String, String> compensations) {
        try {
            return objectMapper.writeValueAsString(compensations);
        } catch (JsonProcessingException e) {
            throw new RuntimeException("Failed to serialize compensations for example flow seeding", e);
        }
    }
}
