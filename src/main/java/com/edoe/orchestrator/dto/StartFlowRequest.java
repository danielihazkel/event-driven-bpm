package com.edoe.orchestrator.dto;

import java.util.Map;

public record StartFlowRequest(String definitionName, Map<String, Object> initialData) {}
