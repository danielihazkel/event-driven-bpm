package com.edoe.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Request object for creating or updating a process definition")
public record ProcessDefinitionRequest(
        @Schema(description = "The unique name of the process definition", example = "order-fulfillment") String name,
        @Schema(description = "The name of the first step in the process", example = "validate-order") String initialStep,
        @Schema(description = "Map of state transitions, where key is 'currentStep:event' or 'currentStep' and value is 'nextStep'", example = "{\"validate-order:valid\": \"process-payment\"}") Map<String, String> transitions) {
}
