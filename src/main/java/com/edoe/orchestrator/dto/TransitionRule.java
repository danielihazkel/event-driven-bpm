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
        String joinStep) {

    /** Factory for single-next rules (the common case). */
    public static TransitionRule of(String condition, String next) {
        return new TransitionRule(condition, next, null, null);
    }

    /** Factory for fork rules — fans out to all parallel steps, then joins at joinStep. */
    public static TransitionRule fork(List<String> parallel, String joinStep) {
        return new TransitionRule(null, null, parallel, joinStep);
    }

    /** Returns true if this rule represents a parallel fork. */
    @JsonIgnore
    public boolean isFork() {
        return parallel != null && !parallel.isEmpty();
    }
}
