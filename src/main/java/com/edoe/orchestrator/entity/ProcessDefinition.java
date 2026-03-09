package com.edoe.orchestrator.entity;

import jakarta.persistence.*;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Getter
@Setter
@NoArgsConstructor(access = AccessLevel.PROTECTED)
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

}
