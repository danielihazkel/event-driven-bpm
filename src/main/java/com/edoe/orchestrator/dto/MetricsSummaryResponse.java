package com.edoe.orchestrator.dto;

import io.swagger.v3.oas.annotations.media.Schema;

@Schema(description = "Response object containing summary metrics for process instances")
public record MetricsSummaryResponse(
                @Schema(description = "Total number of processes", example = "150") long total,
                @Schema(description = "Number of currently running processes", example = "10") long running,
                @Schema(description = "Number of completed processes", example = "120") long completed,
                @Schema(description = "Number of failed processes", example = "5") long failed,
                @Schema(description = "Number of stalled processes", example = "2") long stalled,
                @Schema(description = "Number of cancelled processes", example = "13") long cancelled,
                @Schema(description = "Number of timer-delayed (scheduled) processes", example = "3") long scheduled,
                @Schema(description = "Overall success rate (percentage)", example = "89.5") double successRate) {
}
