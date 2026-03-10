package com.edoe.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;
import java.util.Map;

/**
 * One branch in a conditional transition.
 * Branches are evaluated top-to-bottom; the first whose condition matches wins.
 * A null/blank condition is an unconditional default (always matches).
 *
 * <p><b>Single-next rule</b> (most common) — use {@link #of(String, String)}:</p>
 * <pre>
 * "STEP_1_FINISHED": [
 *   { "condition": "#approved == true", "next": "APPROVE_STEP" },
 *   { "next": "DEFAULT_STEP" }
 * ]
 * </pre>
 *
 * <p><b>Fork rule</b> — use {@link #fork(List, String)}. When {@code parallel} is set,
 * the engine fans out to all listed steps simultaneously and waits for all of them
 * to finish before advancing to {@code joinStep}.</p>
 * <pre>
 * "PREPARE_FINISHED": [
 *   { "parallel": ["VALIDATE_CREDIT", "VERIFY_IDENTITY"], "joinStep": "APPROVE_LOAN" }
 * ]
 * </pre>
 *
 * <p><b>Suspend rule</b> — use {@link #suspend(String, String)}. When {@code suspend} is true,
 * the engine parks the process at {@code next} with {@code status=SUSPENDED} and dispatches
 * no command. Resume via {@code POST /api/processes/{id}/signal}.</p>
 * <pre>
 * "VALIDATE_CREDIT_FINISHED": [
 *   { "condition": "#creditScore > 700", "next": "AUTO_APPROVE" },
 *   { "next": "MANUAL_REVIEW", "suspend": true }
 * ]
 * </pre>
 *
 * <p><b>Delay rule</b> — use {@link #delay(Long, String)}. When {@code delayMs} is set,
 * the engine advances to {@code next} but sets {@code status=SCHEDULED} and {@code wakeAt=now+delayMs}.
 * A background timer dispatches the step command once the delay has elapsed.</p>
 * <pre>
 * "PREPARE_REQUEST_FINISHED": [
 *   { "delayMs": 5000, "next": "PROCESS_REQUEST" }
 * ]
 * </pre>
 *
 * <p><b>Call-activity rule</b> — use {@link #callActivity(String, String, String)}. When
 * {@code callActivity} is set, the engine spawns a child process from the named definition,
 * sets the parent to {@code status=WAITING_FOR_CHILD}, and resumes at {@code next} once the
 * child completes.</p>
 * <pre>
 * "COLLECT_APPLICATION_FINISHED": [
 *   { "callActivity": "CREDIT_CHECK_SUB", "next": "MAKE_DECISION" }
 * ]
 * </pre>
 *
 * <p><b>Multi-instance rule</b> — use {@link #multiInstance(String, String, String)}. When
 * {@code multiInstanceVariable} is set, the engine reads that key from {@code context_data}
 * as a {@code List}, dispatches one command per element (indexed as {@code STEP__MI__0},
 * {@code STEP__MI__1}, …), gathers all results into {@code multiInstanceResults} in the context,
 * then advances to {@code joinStep}. The per-element step name is carried in {@code next}.</p>
 * <pre>
 * "RECEIVE_ORDERS_FINISHED": [
 *   { "multiInstanceVariable": "orderItems", "next": "PROCESS_ORDER", "joinStep": "SHIP_ORDERS" }
 * ]
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A conditional branch in a process transition")
public record TransitionRule(
        @Schema(description = "SpEL expression evaluated against process context variables (use #varName syntax). Null or blank means unconditional match.",
                example = "#approved == true")
        String condition,

        @Schema(description = "The next step to transition to if this condition matches (single-next rule)",
                example = "APPROVAL_STEP")
        String next,

        @Schema(description = "For fork rules: list of steps to execute in parallel. When all complete, the process advances to joinStep.")
        List<String> parallel,

        @Schema(description = "For fork rules: the step to transition to after all parallel branches have completed.")
        String joinStep,

        @Schema(description = "When true, the process parks at 'next' with status=SUSPENDED and waits for a POST /api/processes/{id}/signal call.")
        Boolean suspend,

        @Schema(description = "For delay rules: milliseconds to wait before dispatching the 'next' step command. The process enters status=SCHEDULED until the timer fires.")
        Long delayMs,

        @Schema(description = "For call-activity rules: name of the child process definition to invoke. The parent enters status=WAITING_FOR_CHILD until the child completes, then advances to 'next'.")
        String callActivity,

        @Schema(description = "For multi-instance rules: context key holding a List. The engine dispatches one command per element (step name from 'next') and gathers results into multiInstanceResults before advancing to 'joinStep'.")
        String multiInstanceVariable,

        @Schema(description = "Optional JSONPath-based output mapping. Keys are target context variable names; values are JSONPath expressions evaluated against the raw worker output. When set, only the mapped fields are written to context_data (unmapped worker output is discarded). When null, the full worker output is shallow-merged (backward-compatible default).",
                example = "{\"creditScore\": \"$.score\", \"creditBureau\": \"$.meta.bureau\"}")
        Map<String, String> outputMapping) {

    /** Factory for single-next rules (the common case). */
    public static TransitionRule of(String condition, String next) {
        return new TransitionRule(condition, next, null, null, null, null, null, null, null);
    }

    /** Factory for fork rules — fans out to all parallel steps, then joins at joinStep. */
    public static TransitionRule fork(List<String> parallel, String joinStep) {
        return new TransitionRule(null, null, parallel, joinStep, null, null, null, null, null);
    }

    /** Factory for suspend rules — routes to suspendStep and halts there until a signal arrives. */
    public static TransitionRule suspend(String condition, String suspendStep) {
        return new TransitionRule(condition, suspendStep, null, null, Boolean.TRUE, null, null, null, null);
    }

    /** Factory for delay rules — advances to next after delayMs milliseconds. */
    public static TransitionRule delay(Long delayMs, String next) {
        return new TransitionRule(null, next, null, null, null, delayMs, null, null, null);
    }

    /** Factory for call-activity rules — spawns a child process, then advances parent to nextAfterChild. */
    public static TransitionRule callActivity(String condition, String childDefinition, String nextAfterChild) {
        return new TransitionRule(condition, nextAfterChild, null, null, null, null, childDefinition, null, null);
    }

    /**
     * Factory for multi-instance (scatter-gather) rules.
     * Reads {@code multiInstanceVariable} from context as a List, dispatches one
     * {@code miStep__MI__N} command per element, then advances to {@code joinStep}.
     */
    public static TransitionRule multiInstance(String multiInstanceVariable, String miStep, String joinStep) {
        return new TransitionRule(null, miStep, null, joinStep, null, null, null, multiInstanceVariable, null);
    }

    /**
     * Conditional variant of the multi-instance factory.
     */
    public static TransitionRule multiInstance(String condition, String multiInstanceVariable, String miStep, String joinStep) {
        return new TransitionRule(condition, miStep, null, joinStep, null, null, null, multiInstanceVariable, null);
    }

    /**
     * Factory for single-next rules with precise JSONPath output mapping.
     * Only the context variables named in {@code outputMapping} are extracted
     * from the worker output and persisted to {@code context_data}; all other
     * worker output fields are discarded.
     *
     * <p>Example — extract {@code $.score} and {@code $.meta.bureau} from the
     * worker output and place them under {@code creditScore} / {@code creditBureau}:
     * <pre>
     * TransitionRule.ofMapped("#creditScore > 700", "AUTO_APPROVE",
     *     Map.of("creditScore", "$.score", "creditBureau", "$.meta.bureau"))
     * </pre></p>
     */
    public static TransitionRule ofMapped(String condition, String next, Map<String, String> outputMapping) {
        return new TransitionRule(condition, next, null, null, null, null, null, null, outputMapping);
    }

    /** Returns true if this rule represents a parallel fork. */
    @JsonIgnore
    public boolean isFork() {
        return parallel != null && !parallel.isEmpty();
    }

    /**
     * Returns true if this rule causes the process to suspend at the next step.
     * Named {@code isSuspendGate} (not {@code isSuspend}) to avoid Java Bean naming
     * collision: {@code isSuspend()} would be treated as a bean getter for the
     * {@code "suspend"} property, and the {@code @JsonIgnore} would prevent Jackson
     * from deserializing the {@code suspend} JSON field.
     */
    @JsonIgnore
    public boolean isSuspendGate() {
        return Boolean.TRUE.equals(suspend);
    }

    /** Returns true if this rule represents a timer delay before dispatching the next step. */
    @JsonIgnore
    public boolean isDelay() {
        return delayMs != null;
    }

    /**
     * Returns true if this rule spawns a child process via call activity.
     * Named {@code isCallActivityRule} (not {@code isCallActivity}) to avoid
     * Java Bean naming collision with the {@code callActivity} record component.
     */
    @JsonIgnore
    public boolean isCallActivityRule() {
        return callActivity != null && !callActivity.isBlank();
    }

    /**
     * Returns true if this rule triggers multi-instance scatter-gather execution.
     * Named {@code isMultiInstanceRule} (not {@code isMultiInstance}) to avoid
     * Java Bean naming collision with the {@code multiInstanceVariable} component.
     */
    @JsonIgnore
    public boolean isMultiInstanceRule() {
        return multiInstanceVariable != null && !multiInstanceVariable.isBlank();
    }

    /**
     * Returns true if this rule has an explicit JSONPath output mapping.
     * Named {@code hasOutputMapping} (not {@code isOutputMapping}) to avoid
     * any Java Bean getter collision with the {@code outputMapping} component.
     */
    @JsonIgnore
    public boolean hasOutputMapping() {
        return outputMapping != null && !outputMapping.isEmpty();
    }
}
