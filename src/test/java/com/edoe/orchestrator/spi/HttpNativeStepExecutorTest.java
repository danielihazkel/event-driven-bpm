package com.edoe.orchestrator.spi;

import com.edoe.orchestrator.dto.HttpRequestConfig;
import com.edoe.orchestrator.dto.TransitionRule;
import com.edoe.orchestrator.service.HttpStepExecutor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class HttpNativeStepExecutorTest {

    @Mock
    private HttpStepExecutor httpStepExecutor;

    private HttpNativeStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new HttpNativeStepExecutor(httpStepExecutor);
    }

    // 1. canHandle returns true when rule has httpRequest
    @Test
    void canHandle_returnsTrueWhenRuleHasHttpRequest() {
        HttpRequestConfig config = new HttpRequestConfig("https://example.com", "GET", null, null);
        TransitionRule rule = TransitionRule.httpStep(null, config, "NEXT_STEP");
        assertThat(executor.canHandle(rule)).isTrue();
    }

    // 2. canHandle returns false when rule has no httpRequest
    @Test
    void canHandle_returnsFalseWhenRuleHasNoHttpRequest() {
        TransitionRule rule = TransitionRule.of(null, "NEXT_STEP");
        assertThat(executor.canHandle(rule)).isFalse();
    }

    // 3. execute: successful HTTP result returns NativeStepResult(true, output)
    @Test
    void execute_successfulHttpResult_returnsSuccessNativeResult() {
        HttpRequestConfig config = new HttpRequestConfig("https://example.com", "GET", null, null);
        TransitionRule rule = TransitionRule.httpStep(null, config, "NEXT_STEP");
        Map<String, Object> output = Map.of("key", "value");

        when(httpStepExecutor.execute(eq("STEP_1"), eq(config), any()))
                .thenReturn(new HttpStepExecutor.HttpStepResult(true, output));

        NativeStepResult result = executor.execute("STEP_1", rule, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("key", "value");
    }

    // 4. execute: failed HTTP result returns NativeStepResult(false, output)
    @Test
    void execute_failedHttpResult_returnsFailureNativeResult() {
        HttpRequestConfig config = new HttpRequestConfig("https://example.com", "GET", null, null);
        TransitionRule rule = TransitionRule.httpStep(null, config, "NEXT_STEP");
        Map<String, Object> errorOutput = Map.of("httpError", "500 Internal Server Error");

        when(httpStepExecutor.execute(eq("STEP_1"), eq(config), any()))
                .thenReturn(new HttpStepExecutor.HttpStepResult(false, errorOutput));

        NativeStepResult result = executor.execute("STEP_1", rule, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).containsEntry("httpError", "500 Internal Server Error");
    }

    // 5. execute passes context through to HttpStepExecutor
    @Test
    void execute_passesContextThroughToHttpStepExecutor() {
        HttpRequestConfig config = new HttpRequestConfig("https://example.com", "POST", null, null);
        TransitionRule rule = TransitionRule.httpStep(null, config, "NEXT_STEP");
        Map<String, Object> context = Map.of("orderId", "123");

        when(httpStepExecutor.execute(any(), any(), eq(context)))
                .thenReturn(new HttpStepExecutor.HttpStepResult(true, Map.of()));

        executor.execute("STEP_1", rule, context);

        verify(httpStepExecutor).execute(eq("STEP_1"), eq(config), eq(context));
    }
}
