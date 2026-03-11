package com.edoe.orchestrator.controller;

import com.edoe.orchestrator.dto.WebhookSubscriptionRequest;
import com.edoe.orchestrator.dto.WebhookSubscriptionResponse;
import com.edoe.orchestrator.service.WebhookService;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
public class WebhookController {

    private final WebhookService webhookService;

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WebhookSubscriptionResponse create(@RequestBody WebhookSubscriptionRequest request) {
        return webhookService.createSubscription(request);
    }

    @GetMapping
    public Page<WebhookSubscriptionResponse> list(Pageable pageable) {
        return webhookService.listSubscriptions(pageable);
    }

    @GetMapping("/{id}")
    public WebhookSubscriptionResponse get(@PathVariable UUID id) {
        return webhookService.getSubscription(id);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID id) {
        webhookService.deleteSubscription(id);
    }

    @PatchMapping("/{id}/toggle")
    public WebhookSubscriptionResponse toggle(@PathVariable UUID id) {
        return webhookService.toggleActive(id);
    }
}
