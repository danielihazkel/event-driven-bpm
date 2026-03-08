package com.edoe.orchestrator.repository;

import com.edoe.orchestrator.entity.ProcessInstance;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.LocalDateTime;
import java.util.List;
import java.util.UUID;

public interface ProcessInstanceRepository extends JpaRepository<ProcessInstance, UUID> {

    @Query("SELECT p FROM ProcessInstance p WHERE p.status = com.edoe.orchestrator.entity.ProcessStatus.RUNNING AND p.stepStartedAt < :threshold")
    List<ProcessInstance> findStalledProcesses(@Param("threshold") LocalDateTime threshold);
}
