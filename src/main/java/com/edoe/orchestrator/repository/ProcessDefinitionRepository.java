package com.edoe.orchestrator.repository;

import com.edoe.orchestrator.entity.ProcessDefinition;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;

public interface ProcessDefinitionRepository extends JpaRepository<ProcessDefinition, Long> {

    Optional<ProcessDefinition> findByName(String name);

    boolean existsByName(String name);
}
