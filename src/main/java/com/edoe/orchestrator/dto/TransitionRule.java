package com.edoe.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

import java.util.List;

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
        String callActivity) {

    /** Factory for single-next rules (the common case). */
    public static TransitionRule of(String condition, String next) {
        return new TransitionRule(condition, next, null, null, null, null, null);
    }

    /** Factory for fork rules — fans out to all parallel steps, then joins at joinStep. */
    public static TransitionRule fork(List<String> parallel, String joinStep) {
        return new TransitionRule(null, null, parallel, joinStep, null, null, null);
    }

    /** Factory for suspend rules — routes to suspendStep and halts there until a signal arrives. */
    public static TransitionRule suspend(String condition, String suspendStep) {
        return new TransitionRule(condition, suspendStep, null, null, Boolean.TRUE, null, null);
    }

    /** Factory for delay rules — advances to next after delayMs milliseconds. */
    public static TransitionRule delay(Long delayMs, String next) {
        return new TransitionRule(null, next, null, null, null, delayMs, null);
    }

    /** Factory for call-activity rules — spawns a child process, then advances parent to nextAfterChild. */
    public static TransitionRule callActivity(String condition, String childDefinition, String nextAfterChild) {
        return new TransitionRule(condition, nextAfterChild, null, null, null, null, childDefinition);
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
}
