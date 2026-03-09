package com.edoe.orchestrator.entity;

import jakarta.persistence.*;
import java.time.LocalDateTime;

@Entity
@Table(name = "process_definitions")
public class ProcessDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(nullable = false, unique = true)
    private String name;

    @Column(name = "initial_step", nullable = false)
    private String initialStep;

    @Column(name = "transitions_json", columnDefinition = "TEXT", nullable = false)
    private String transitionsJson;

    @Column(name = "compensations_json", columnDefinition = "TEXT")
    private String compensationsJson;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    protected ProcessDefinition() {
    }

    public ProcessDefinition(String name, String initialStep, String transitionsJson) {
        this.name = name;
        this.initialStep = initialStep;
        this.transitionsJson = transitionsJson;
        this.createdAt = LocalDateTime.now();
        this.updatedAt = LocalDateTime.now();
    }

    public ProcessDefinition(String name, String initialStep, String transitionsJson, String compensationsJson) {
        this(name, initialStep, transitionsJson);
        this.compensationsJson = compensationsJson;
    }

    public Long getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getInitialStep() {
        return initialStep;
    }

    public String getTransitionsJson() {
        return transitionsJson;
    }

    public String getCompensationsJson() {
        return compensationsJson;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setInitialStep(String initialStep) {
        this.initialStep = initialStep;
    }

    public void setTransitionsJson(String transitionsJson) {
        this.transitionsJson = transitionsJson;
    }

    public void setCompensationsJson(String compensationsJson) {
        this.compensationsJson = compensationsJson;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
