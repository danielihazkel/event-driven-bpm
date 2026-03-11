package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.dto.WebhookSubscriptionRequest;
import com.edoe.orchestrator.dto.WebhookSubscriptionResponse;
import com.edoe.orchestrator.service.WebhookService;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.data.domain.PageImpl;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.time.LocalDateTime;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest({ WebhookController.class, GlobalExceptionHandler.class })
class WebhookControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockBean
    private WebhookService webhookService;

    private WebhookSubscriptionResponse sampleResponse(UUID id) {
        return new WebhookSubscriptionResponse(
                id, "TEST_FLOW", "https://example.com/hook",
                List.of("COMPLETED", "FAILED"), true, LocalDateTime.now());
    }

    @Test
    void createSubscription_returns201() throws Exception {
        UUID id = UUID.randomUUID();
        WebhookSubscriptionRequest req = new WebhookSubscriptionRequest(
                "TEST_FLOW", "https://example.com/hook", List.of("COMPLETED"), null);
        when(webhookService.createSubscription(any())).thenReturn(sampleResponse(id));

        mockMvc.perform(post("/api/webhooks")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(req)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.targetUrl").value("https://example.com/hook"));
    }

    @Test
    void listSubscriptions_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(webhookService.listSubscriptions(any()))
                .thenReturn(new PageImpl<>(List.of(sampleResponse(id))));

        mockMvc.perform(get("/api/webhooks"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].targetUrl").value("https://example.com/hook"));
    }

    @Test
    void getSubscription_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        when(webhookService.getSubscription(id)).thenReturn(sampleResponse(id));

        mockMvc.perform(get("/api/webhooks/{id}", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id.toString()));
    }

    @Test
    void deleteSubscription_returns204() throws Exception {
        UUID id = UUID.randomUUID();
        doNothing().when(webhookService).deleteSubscription(id);

        mockMvc.perform(delete("/api/webhooks/{id}", id))
                .andExpect(status().isNoContent());
    }

    @Test
    void toggleSubscription_returns200() throws Exception {
        UUID id = UUID.randomUUID();
        WebhookSubscriptionResponse toggled = new WebhookSubscriptionResponse(
                id, "TEST_FLOW", "https://example.com/hook",
                List.of("COMPLETED"), false, LocalDateTime.now());
        when(webhookService.toggleActive(id)).thenReturn(toggled);

        mockMvc.perform(patch("/api/webhooks/{id}/toggle", id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.active").value(false));
    }
}
