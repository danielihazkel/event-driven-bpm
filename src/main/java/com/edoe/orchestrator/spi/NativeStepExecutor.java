package com.edoe.orchestrator.spi;

import com.edoe.orchestrator.dto.TransitionRule;
import java.util.Map;

public interface NativeStepExecutor {

    /** Returns true if this executor owns the given rule. First match wins. */
    boolean canHandle(TransitionRule rule);

    /** Executes the step. Must NOT modify ProcessInstance — TransitionService owns state. */
    NativeStepResult execute(String stepName, TransitionRule rule, Map<String, Object> context);
}
