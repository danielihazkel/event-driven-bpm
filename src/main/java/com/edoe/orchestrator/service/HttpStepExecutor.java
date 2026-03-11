package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.HttpRequestConfig;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.expression.ExpressionParser;
import org.springframework.expression.spel.standard.SpelExpressionParser;
import org.springframework.expression.spel.support.StandardEvaluationContext;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Service;
import org.springframework.web.client.HttpStatusCodeException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.HashMap;
import java.util.Map;

/**
 * Executes a native HTTP call on behalf of the orchestrator when a
 * {@link com.edoe.orchestrator.dto.TransitionRule} carries an
 * {@link HttpRequestConfig}. The call is synchronous and blocking; it runs
 * inside the caller's transaction in {@link TransitionService}.
 *
 * <p>SpEL evaluation rules for string fields in {@link HttpRequestConfig}:</p>
 * <ul>
 *   <li>If the string contains {@code #} or {@code T(}, it is treated as a SpEL
 *       expression evaluated against the current process context.</li>
 *   <li>Otherwise it is used verbatim (literal).</li>
 * </ul>
 *
 * <p>A 2xx response with a JSON-object body is parsed into a
 * {@code Map<String,Object>} and returned as the step output.
 * Non-JSON or non-object responses are wrapped under {@code "httpResponse"}.
 * Any 4xx / 5xx / network error returns {@code success=false} with error
 * details in the output map.</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class HttpStepExecutor {

    /**
     * Result of a single HTTP step execution.
     *
     * @param success {@code true} if the HTTP call returned a 2xx status.
     * @param output  parsed response body (on success) or error details (on failure).
     */
    public record HttpStepResult(boolean success, Map<String, Object> output) {}

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final ExpressionParser spelParser = new SpelExpressionParser();

    /**
     * Executes the HTTP call described by {@code config}, evaluating SpEL
     * expressions against {@code context}.
     *
     * @param stepName the current process step name (used for logging)
     * @param config   the HTTP request configuration
     * @param context  the current process context (SpEL variable root)
     * @return result indicating success/failure and response payload
     */
    public HttpStepResult execute(String stepName, HttpRequestConfig config, Map<String, Object> context) {
        StandardEvaluationContext evalContext = new StandardEvaluationContext();
        if (context != null) {
            context.forEach(evalContext::setVariable);
        }

        // Resolve URL
        String resolvedUrl;
        try {
            resolvedUrl = resolveSpel(config.url(), evalContext);
        } catch (Exception e) {
            log.error("HTTP step {}: URL SpEL evaluation failed: {}", stepName, e.getMessage());
            return new HttpStepResult(false, Map.of("httpError", "URL eval failed: " + e.getMessage(), "step", stepName));
        }

        // Build headers
        HttpHeaders headers = new HttpHeaders();
        if (config.headers() != null) {
            for (Map.Entry<String, String> entry : config.headers().entrySet()) {
                try {
                    String value = resolveSpel(entry.getValue(), evalContext);
                    headers.set(entry.getKey(), value != null ? value : entry.getValue());
                } catch (Exception e) {
                    log.warn("HTTP step {}: header '{}' SpEL eval failed, using literal: {}", stepName, entry.getKey(), e.getMessage());
                    headers.set(entry.getKey(), entry.getValue());
                }
            }
        }

        // Resolve body
        String resolvedBody = null;
        if (config.body() != null) {
            try {
                resolvedBody = resolveSpel(config.body(), evalContext);
            } catch (Exception e) {
                log.warn("HTTP step {}: body SpEL eval failed, using literal: {}", stepName, e.getMessage());
                resolvedBody = config.body();
            }
            if (!headers.containsKey(HttpHeaders.CONTENT_TYPE)) {
                headers.setContentType(MediaType.APPLICATION_JSON);
            }
        }

        HttpEntity<String> entity = new HttpEntity<>(resolvedBody, headers);
        HttpMethod method = HttpMethod.valueOf(config.method().toUpperCase());

        try {
            ResponseEntity<String> response = restTemplate.exchange(resolvedUrl, method, entity, String.class);
            Map<String, Object> output = parseResponseBody(response.getBody(), response.getStatusCode().value());
            log.info("HTTP step {} completed: {} {} → {}", stepName, method, resolvedUrl, response.getStatusCode().value());
            return new HttpStepResult(true, output);
        } catch (HttpStatusCodeException e) {
            log.warn("HTTP step {} failed: {} {} → {}", stepName, method, resolvedUrl, e.getStatusCode());
            return new HttpStepResult(false, Map.of(
                    "httpError", e.getResponseBodyAsString(),
                    "httpStatusCode", e.getStatusCode().value(),
                    "step", stepName));
        } catch (ResourceAccessException e) {
            log.error("HTTP step {}: connection failed: {}", stepName, e.getMessage());
            return new HttpStepResult(false, Map.of("httpError", "Connection failed: " + e.getMessage(), "step", stepName));
        } catch (Exception e) {
            log.error("HTTP step {}: unexpected error: {}", stepName, e.getMessage());
            return new HttpStepResult(false, Map.of("httpError", e.getMessage(), "step", stepName));
        }
    }

    /**
     * Evaluates {@code expression} as SpEL if it contains {@code #} or {@code T(};
     * otherwise returns it verbatim.
     */
    private String resolveSpel(String expression, StandardEvaluationContext context) {
        if (expression == null) return null;
        if (expression.contains("#") || expression.contains("T(")) {
            return spelParser.parseExpression(expression).getValue(context, String.class);
        }
        return expression;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> parseResponseBody(String body, int statusCode) {
        if (body == null || body.isBlank()) {
            return Map.of("httpStatusCode", statusCode);
        }
        try {
            Object parsed = objectMapper.readValue(body, new TypeReference<Object>() {});
            if (parsed instanceof Map<?, ?> map) {
                Map<String, Object> result = new HashMap<>((Map<String, Object>) map);
                result.put("httpStatusCode", statusCode);
                return result;
            } else {
                return Map.of("httpResponse", parsed, "httpStatusCode", statusCode);
            }
        } catch (Exception e) {
            return Map.of("httpResponse", body, "httpStatusCode", statusCode);
        }
    }
}
