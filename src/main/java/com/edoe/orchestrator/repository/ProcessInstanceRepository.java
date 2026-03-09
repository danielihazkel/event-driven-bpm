package com.edoe.orchestrator.repository;

import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, UUID> {

    @Query("SELECT p FROM ProcessInstance p WHERE p.status = com.edoe.orchestrator.entity.ProcessStatus.RUNNING AND p.stepStartedAt < :threshold")
    List<ProcessInstance> findStalledProcesses(@Param("threshold") LocalDateTime threshold);

    Page<ProcessInstance> findByStatus(ProcessStatus status, Pageable pageable);

    Page<ProcessInstance> findByDefinitionName(String definitionName, Pageable pageable);

    Page<ProcessInstance> findByStatusAndDefinitionName(ProcessStatus status, String definitionName, Pageable pageable);

    boolean existsByDefinitionNameAndStatusIn(String definitionName, Collection<ProcessStatus> statuses);

    long countByStatus(ProcessStatus status);

    List<ProcessInstance> findByStatusAndWakeAtLessThanEqual(ProcessStatus status, LocalDateTime now);
}
