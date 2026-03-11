package com.edoe.orchestrator.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.UuidGenerator;

import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "webhook_subscriptions")
@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
public class WebhookSubscription {

    @Id
    @GeneratedValue
    @UuidGenerator
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "process_definition_name")
    private String processDefinitionName;

    @Column(name = "target_url", nullable = false)
    private String targetUrl;

    @Column(name = "events", nullable = false, columnDefinition = "TEXT")
    private String events;

    @Column(name = "secret")
    private String secret;

    @Column(name = "active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    public WebhookSubscription(String processDefinitionName, String targetUrl, String events, String secret) {
        this.processDefinitionName = processDefinitionName;
        this.targetUrl = targetUrl;
        this.events = events;
        this.secret = secret;
        this.active = true;
        this.createdAt = LocalDateTime.now();
    }
}
