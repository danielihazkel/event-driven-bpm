package com.edoe.orchestrator.dto;

import java.time.LocalDateTime;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response object containing details of a process definition")
public record ProcessDefinitionResponse(
                @Schema(description = "Internal ID of the process definition", example = "1") Long id,
                @Schema(description = "The unique name of the process definition", example = "order-fulfillment") String name,
                @Schema(description = "The name of the first step in the process", example = "validate-order") String initialStep,
                @Schema(description = "Map of state transitions") Map<String, String> transitions,
                @Schema(description = "Timestamp when the definition was created") LocalDateTime createdAt,
                @Schema(description = "Timestamp when the definition was last updated") LocalDateTime updatedAt) {
}
