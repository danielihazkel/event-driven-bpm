package com.edoe.orchestrator.worker;

import java.util.Map;

public interface WorkerTask {
    String taskType();
    Map<String, Object> execute(Map<String, Object> inputData);
}
