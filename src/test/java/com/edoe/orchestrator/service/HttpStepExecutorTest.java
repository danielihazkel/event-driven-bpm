package com.edoe.orchestrator.service;

import com.edoe.orchestrator.dto.HttpRequestConfig;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class HttpStepExecutorTest {

    @Mock
    private RestTemplate restTemplate;

    private HttpStepExecutor executor;

    @BeforeEach
    void setUp() {
        executor = new HttpStepExecutor(restTemplate, new ObjectMapper());
    }

    // 1. Successful GET with JSON object body — response parsed into map
    @Test
    void execute_successfulGet_returnsParsedBody() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"userId\":42,\"title\":\"test\"}"));

        HttpRequestConfig config = new HttpRequestConfig("https://api.example.com/data", "GET", null, null);
        HttpStepExecutor.HttpStepResult result = executor.execute("MY_STEP", config, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("userId", 42);
        assertThat(result.output()).containsEntry("title", "test");
    }

    // 2. Successful POST with body
    @Test
    void execute_successfulPost_returnsParsedBody() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.POST), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"id\":99}"));

        HttpRequestConfig config = new HttpRequestConfig(
                "https://api.example.com/items", "POST",
                Map.of("Content-Type", "application/json"),
                "{\"name\":\"widget\"}");
        HttpStepExecutor.HttpStepResult result = executor.execute("CREATE_ITEM", config, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsEntry("id", 99);
    }

    // 3. HTTP 4xx response — returns failure with error details
    @Test
    void execute_http4xxResponse_returnsFailure() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpClientErrorException(HttpStatus.NOT_FOUND, "Not Found"));

        HttpRequestConfig config = new HttpRequestConfig("https://api.example.com/missing", "GET", null, null);
        HttpStepExecutor.HttpStepResult result = executor.execute("MY_STEP", config, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).containsKey("httpError");
        assertThat(result.output()).containsEntry("httpStatusCode", 404);
    }

    // 4. HTTP 5xx response — returns failure
    @Test
    void execute_http5xxResponse_returnsFailure() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new HttpServerErrorException(HttpStatus.INTERNAL_SERVER_ERROR, "Server Error"));

        HttpRequestConfig config = new HttpRequestConfig("https://api.example.com/broken", "GET", null, null);
        HttpStepExecutor.HttpStepResult result = executor.execute("MY_STEP", config, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).containsKey("httpError");
        assertThat(result.output()).containsEntry("httpStatusCode", 500);
    }

    // 5. Network / connection failure — returns failure
    @Test
    void execute_connectionFailure_returnsFailure() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenThrow(new ResourceAccessException("Connection refused"));

        HttpRequestConfig config = new HttpRequestConfig("https://unreachable.example.com", "GET", null, null);
        HttpStepExecutor.HttpStepResult result = executor.execute("MY_STEP", config, Map.of());

        assertThat(result.success()).isFalse();
        assertThat(result.output()).containsKey("httpError");
    }

    // 6. SpEL expression in URL resolved against process context
    @Test
    void execute_spelUrlResolution_evaluatesExpression() {
        when(restTemplate.exchange(eq("https://api.example.com/users/42"), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("{\"name\":\"Alice\"}"));

        HttpRequestConfig config = new HttpRequestConfig(
                "'https://api.example.com/users/' + #userId", "GET", null, null);
        HttpStepExecutor.HttpStepResult result = executor.execute("FETCH_USER", config, Map.of("userId", 42));

        assertThat(result.success()).isTrue();
        verify(restTemplate).exchange(eq("https://api.example.com/users/42"), eq(HttpMethod.GET), any(), eq(String.class));
    }

    // 7. Non-JSON response body — wrapped under "httpResponse"
    @Test
    void execute_nonJsonResponseBody_wrapsInMap() {
        when(restTemplate.exchange(anyString(), eq(HttpMethod.GET), any(), eq(String.class)))
                .thenReturn(ResponseEntity.ok("OK"));

        HttpRequestConfig config = new HttpRequestConfig("https://api.example.com/ping", "GET", null, null);
        HttpStepExecutor.HttpStepResult result = executor.execute("PING", config, Map.of());

        assertThat(result.success()).isTrue();
        assertThat(result.output()).containsKey("httpResponse");
        assertThat(result.output().get("httpResponse")).isEqualTo("OK");
    }
}
