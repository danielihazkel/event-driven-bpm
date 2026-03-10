package com.edoe.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.Map;

@Schema(description = "Request object to start a new instance of a process flow")
public record StartFlowRequest(
        @Schema(description = "The name of the process definition to instantiate", example = "order-fulfillment") String definitionName,
        @Schema(description = "Specific definition version to use; omit or null to use the latest version", example = "1") Integer definitionVersion,
        @Schema(description = "Initial context data to pass to the first step of the process", example = "{\"orderId\": \"12345\", \"customerId\": \"cust-001\"}") Map<String, Object> initialData) {
}
