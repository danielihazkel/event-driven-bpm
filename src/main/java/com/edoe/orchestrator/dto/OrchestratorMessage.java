package com.edoe.orchestrator.dto;

import java.util.Map;

public record OrchestratorMessage(String processId, String type, Map<String, Object> data) {}
