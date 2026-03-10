package com.edoe.orchestrator.dto;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response object containing details of a process definition")
public record ProcessDefinitionResponse(
        @Schema(description = "Internal ID of the process definition", example = "1") Long id,
        @Schema(description = "The unique name of the process definition", example = "order-fulfillment") String name,
        @Schema(description = "Version number of this definition snapshot", example = "1") int version,
        @Schema(description = "The name of the first step in the process", example = "VALIDATE_ORDER") String initialStep,
        @Schema(description = "Map of event type to ordered list of conditional branches") Map<String, List<TransitionRule>> transitions,
        @Schema(description = "Map of step name to its compensating step name") Map<String, String> compensations,
        @Schema(description = "Timestamp when the definition was created") LocalDateTime createdAt,
        @Schema(description = "Timestamp when the definition was last updated") LocalDateTime updatedAt) {
}
