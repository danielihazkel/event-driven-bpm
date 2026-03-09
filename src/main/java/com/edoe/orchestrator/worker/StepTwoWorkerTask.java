package com.edoe.orchestrator.worker;

import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.Map;

@Slf4j
@Component
public class StepTwoWorkerTask implements WorkerTask {

    @Override
    public String taskType() {
        return "STEP_2";
    }

    @Override
    public Map<String, Object> execute(Map<String, Object> inputData) {
        log.debug("Executing STEP_2 with input: {}", inputData);
        try {
            Thread.sleep(500);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        return Map.of("step2Result", "done");
    }
}
