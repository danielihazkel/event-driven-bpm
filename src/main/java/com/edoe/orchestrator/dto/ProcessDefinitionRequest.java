package com.edoe.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;
import java.util.Map;

@Schema(description = "Request object for creating or updating a process definition")
public record ProcessDefinitionRequest(
        @Schema(description = "The unique name of the process definition", example = "order-fulfillment")
        String name,

        @Schema(description = "The name of the first step in the process", example = "VALIDATE_ORDER")
        String initialStep,

        @Schema(description = "Map of event type to ordered list of conditional branches. Branches are evaluated top-to-bottom; first match wins. A null condition is an unconditional default.")
        Map<String, List<TransitionRule>> transitions) {
}
