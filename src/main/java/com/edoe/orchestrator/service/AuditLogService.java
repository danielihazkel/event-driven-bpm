package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.ProcessAuditLog;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

@Slf4j
@RequiredArgsConstructor
@Service
public class AuditLogService {

    private final AuditLogRepository auditLogRepository;
    private final ObjectMapper objectMapper;

    public void record(UUID processId, AuditEventType eventType,
                       String stepName,
                       ProcessStatus fromStatus, ProcessStatus toStatus,
                       Object payload) {
        String payloadJson = null;
        if (payload != null) {
            try {
                payloadJson = objectMapper.writeValueAsString(payload);
            } catch (JsonProcessingException e) {
                log.warn("AuditLogService: failed to serialize payload for event {}: {}", eventType, e.getMessage());
            }
        }

        String from = fromStatus != null ? fromStatus.name() : null;
        String to = toStatus != null ? toStatus.name() : null;

        ProcessAuditLog entry = new ProcessAuditLog(processId, eventType, stepName, from, to, payloadJson);
        auditLogRepository.save(entry);
    }

    public List<ProcessAuditLog> getAuditTrail(UUID processId) {
        return auditLogRepository.findByProcessIdOrderByOccurredAtAsc(processId);
    }
}
