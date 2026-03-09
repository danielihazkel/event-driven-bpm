package com.edoe.orchestrator.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class StepOneWorkerTask implements WorkerTask {

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
