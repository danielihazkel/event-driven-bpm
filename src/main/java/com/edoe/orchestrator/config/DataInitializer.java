package com.edoe.orchestrator.config;

import com.edoe.orchestrator.dto.TransitionRule;
import com.edoe.orchestrator.entity.ProcessDefinition;
import com.edoe.orchestrator.repository.ProcessDefinitionRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * Seeds example process definitions on every startup using upsert semantics.
 * If a definition already exists it is updated in place; otherwise it is created.
 * This ensures the example flows always reflect the latest engine features after a restart.
 *
 * When a new engine feature is added, update the relevant flow (or add a new one) here.
 */
@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final ProcessDefinitionRepository definitionRepository;
    private final ObjectMapper objectMapper;

    public DataInitializer(ProcessDefinitionRepository definitionRepository, ObjectMapper objectMapper) {
        this.definitionRepository = definitionRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    public void run(String... args) {
        seedDefaultFlow();
        seedLoanApproval();
        seedOrderFulfillment();
        seedParallelFlow();
    }

    // -------------------------------------------------------------------------
    // DEFAULT_FLOW — simple linear two-step sequence
    // Feature: basic step chaining
    // -------------------------------------------------------------------------
    private void seedDefaultFlow() {
        upsert("DEFAULT_FLOW", "STEP_1", Map.of(
                "STEP_1_FINISHED", List.of(TransitionRule.of(null,"STEP_2")),
                "STEP_2_FINISHED", List.of(TransitionRule.of(null,"COMPLETED"))
        ));
    }

    // -------------------------------------------------------------------------
    // LOAN_APPROVAL — credit-score based approval workflow
    // Features: conditional branching (SpEL), multiple terminal paths
    //
    // Happy path (creditScore > 700):
    //   VALIDATE_CREDIT → AUTO_APPROVE → DISBURSE_FUNDS → COMPLETED
    // Low-score path (creditScore <= 700, then approved=true from manual review):
    //   VALIDATE_CREDIT → MANUAL_REVIEW → DISBURSE_FUNDS → COMPLETED
    // Rejection path (creditScore <= 700, then approved=false):
    //   VALIDATE_CREDIT → MANUAL_REVIEW → SEND_REJECTION → COMPLETED
    // -------------------------------------------------------------------------
    private void seedLoanApproval() {
        upsert("LOAN_APPROVAL", "VALIDATE_CREDIT", Map.of(
                "VALIDATE_CREDIT_FINISHED", List.of(
                        TransitionRule.of("#creditScore > 700", "AUTO_APPROVE"),
                        TransitionRule.of(null,"MANUAL_REVIEW")          // default branch
                ),
                "AUTO_APPROVE_FINISHED", List.of(
                        TransitionRule.of(null,"DISBURSE_FUNDS")
                ),
                "MANUAL_REVIEW_FINISHED", List.of(
                        TransitionRule.of("#approved == true", "DISBURSE_FUNDS"),
                        TransitionRule.of(null,"SEND_REJECTION")         // default branch
                ),
                "DISBURSE_FUNDS_FINISHED", List.of(TransitionRule.of(null,"COMPLETED")),
                "SEND_REJECTION_FINISHED",  List.of(TransitionRule.of(null,"COMPLETED"))
        ));
    }

    // -------------------------------------------------------------------------
    // ORDER_FULFILLMENT — e-commerce order processing
    // Features: longer chain, conditional branching at two decision points,
    //           multiple terminal paths all converging to COMPLETED
    //
    // Happy path: {"inventoryAvailable": true, "paymentSuccess": true}
    //   VALIDATE_ORDER → RESERVE_INVENTORY → PROCESS_PAYMENT → SHIP_ORDER → COMPLETED
    // Out-of-stock: {"inventoryAvailable": false}
    //   VALIDATE_ORDER → RESERVE_INVENTORY → NOTIFY_OUT_OF_STOCK → COMPLETED
    // Payment failed: {"inventoryAvailable": true, "paymentSuccess": false}
    //   VALIDATE_ORDER → RESERVE_INVENTORY → PROCESS_PAYMENT → NOTIFY_PAYMENT_FAILED → COMPLETED
    // -------------------------------------------------------------------------
    private void seedOrderFulfillment() {
        upsert("ORDER_FULFILLMENT", "VALIDATE_ORDER", Map.of(
                "VALIDATE_ORDER_FINISHED", List.of(
                        TransitionRule.of(null,"RESERVE_INVENTORY")
                ),
                "RESERVE_INVENTORY_FINISHED", List.of(
                        TransitionRule.of("#inventoryAvailable == true", "PROCESS_PAYMENT"),
                        TransitionRule.of(null,"NOTIFY_OUT_OF_STOCK")    // default branch
                ),
                "PROCESS_PAYMENT_FINISHED", List.of(
                        TransitionRule.of("#paymentSuccess == true", "SHIP_ORDER"),
                        TransitionRule.of(null,"NOTIFY_PAYMENT_FAILED")  // default branch
                ),
                "SHIP_ORDER_FINISHED",           List.of(TransitionRule.of(null,"COMPLETED")),
                "NOTIFY_OUT_OF_STOCK_FINISHED",  List.of(TransitionRule.of(null,"COMPLETED")),
                "NOTIFY_PAYMENT_FAILED_FINISHED", List.of(TransitionRule.of(null,"COMPLETED"))
        ));
    }

    // -------------------------------------------------------------------------
    // PARALLEL_FLOW — demonstrates parallel fork / join (Phase 7)
    // Features: fan-out to multiple steps, wait for all to complete, then join
    //
    // Flow:
    //   PREPARE_APPLICATION → [VALIDATE_CREDIT ∥ VERIFY_IDENTITY] → APPROVE_LOAN → COMPLETED
    // -------------------------------------------------------------------------
    private void seedParallelFlow() {
        upsert("PARALLEL_FLOW", "PREPARE_APPLICATION", Map.of(
                "PREPARE_APPLICATION_FINISHED", List.of(
                        TransitionRule.fork(
                                List.of("VALIDATE_CREDIT", "VERIFY_IDENTITY"),
                                "APPROVE_LOAN")
                ),
                "APPROVE_LOAN_FINISHED", List.of(TransitionRule.of(null, "COMPLETED"))
        ));
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void upsert(String name, String initialStep, Map<String, List<TransitionRule>> transitions) {
        String transitionsJson = serialize(transitions);
        Optional<ProcessDefinition> existing = definitionRepository.findByName(name);
        if (existing.isPresent()) {
            ProcessDefinition def = existing.get();
            def.setInitialStep(initialStep);
            def.setTransitionsJson(transitionsJson);
            def.setUpdatedAt(LocalDateTime.now());
            definitionRepository.save(def);
            log.info("Updated example flow: {}", name);
        } else {
            definitionRepository.save(new ProcessDefinition(name, initialStep, transitionsJson));
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
}
