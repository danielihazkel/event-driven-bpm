package com.edoe.orchestrator.worker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import java.util.Map;

@Component
public class StepOneWorkerTask implements WorkerTask {

    private static final Logger log = LoggerFactory.getLogger(StepOneWorkerTask.class);

    @Override
    public String taskType() {
        return "STEP_1";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputData) {
        log.debug("Executing STEP_1 with input: {}", inputData);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Map.of("step1Result", "done");
    }
}
