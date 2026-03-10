package com.edoe.orchestrator.dto;

import com.edoe.orchestrator.entity.ProcessStatus;

import io.swagger.v3.oas.annotations.media.Schema;

import java.time.LocalDateTime;
import java.util.UUID;

@Schema(description = "Response object containing details of a process instance")
public record ProcessInstanceResponse(
                @Schema(description = "Unique ID of the process instance", example = "123e4567-e89b-12d3-a456-426614174000") UUID id,
                @Schema(description = "The name of the process definition this instance belongs to", example = "order-fulfillment") String definitionName,
                @Schema(description = "The definition version this instance was started with", example = "1") int definitionVersion,
                @Schema(description = "The current step the process is at", example = "process-payment") String currentStep,
                @Schema(description = "The status of the process instance") ProcessStatus status,
                @Schema(description = "Timestamp when the process was started") LocalDateTime createdAt,
                @Schema(description = "Timestamp when the current step was started") LocalDateTime stepStartedAt,
                @Schema(description = "Timestamp when the process was completed") LocalDateTime completedAt,
                @Schema(description = "Context data associated with the process as a JSON string", example = "{\"orderId\": \"12345\"}") String contextData,
                @Schema(description = "UUID of the parent process if this is a child sub-process") UUID parentProcessId) {
}
