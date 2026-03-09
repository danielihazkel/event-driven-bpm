package com.edoe.orchestrator.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import io.swagger.v3.oas.annotations.media.Schema;

/**
 * One branch in a conditional transition.
 * Branches are evaluated top-to-bottom; the first whose condition matches wins.
 * A null/blank condition is an unconditional default (always matches).
 *
 * Conditions are Spring Expression Language (SpEL) expressions evaluated against
 * the merged process context. Each context key is available as a SpEL variable
 * using the {@code #varName} syntax.
 *
 * Example definition:
 * <pre>
 * "STEP_1_FINISHED": [
 *   { "condition": "#approved == true", "next": "APPROVE_STEP" },
 *   { "condition": "#amount > 1000",    "next": "HIGH_VALUE_STEP" },
 *   { "next": "DEFAULT_STEP" }
 * ]
 * </pre>
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
@Schema(description = "A conditional branch in a process transition")
public record TransitionRule(
        @Schema(description = "SpEL expression evaluated against process context variables (use #varName syntax). Null or blank means unconditional match.",
                example = "#approved == true")
        String condition,

        @Schema(description = "The next step to transition to if this condition matches",
                example = "APPROVAL_STEP")
        String next) {
}
