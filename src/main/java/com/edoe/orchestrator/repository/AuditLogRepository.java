package com.edoe.orchestrator.repository;

import com.edoe.orchestrator.entity.ProcessAuditLog;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface AuditLogRepository extends JpaRepository<ProcessAuditLog, UUID> {
    List<ProcessAuditLog> findByProcessIdOrderByOccurredAtAsc(UUID processId);
}
