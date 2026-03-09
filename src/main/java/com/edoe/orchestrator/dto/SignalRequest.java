package com.edoe.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;

import java.util.Map;

/**
 * Request body for {@code POST /api/processes/{id}/signal}.
 * Injects a named event into a SUSPENDED process, resuming it.
 */
@Schema(description = "Signal payload to resume a suspended process")
public record SignalRequest(

        @Schema(description = "The signal event name (e.g. 'APPROVAL_GRANTED'). Matched against transition rule keys in the process definition.",
                example = "APPROVAL_GRANTED")
        String event,

        @Schema(description = "Optional data merged into the process context before evaluating transition conditions.",
                example = "{\"approved\": true}")
        Map<String, Object> data) {
}
