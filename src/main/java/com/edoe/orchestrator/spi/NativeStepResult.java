package com.edoe.orchestrator.spi;

import java.util.Map;

public record NativeStepResult(boolean success, Map<String, Object> output) {}
