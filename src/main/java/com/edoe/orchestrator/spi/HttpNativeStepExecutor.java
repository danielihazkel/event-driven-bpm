package com.edoe.orchestrator.spi;

import com.edoe.orchestrator.dto.TransitionRule;
import com.edoe.orchestrator.service.HttpStepExecutor;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class HttpNativeStepExecutor implements NativeStepExecutor {

    private final HttpStepExecutor httpStepExecutor;

    @Override
    public boolean canHandle(TransitionRule rule) {
        return rule.hasHttpRequest();
    }

    @Override
    public NativeStepResult execute(String stepName, TransitionRule rule, Map<String, Object> context) {
        HttpStepExecutor.HttpStepResult r = httpStepExecutor.execute(stepName, rule.httpRequest(), context);
        return new NativeStepResult(r.success(), r.output());
    }
}
