package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.ProcessAuditLog;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.AuditLogRepository;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AuditLogServiceTest {

    @Mock
    private AuditLogRepository auditLogRepository;

    @Mock
    private ObjectMapper objectMapper;

    private AuditLogService auditLogService;

    @BeforeEach
    void setUp() {
        auditLogService = new AuditLogService(auditLogRepository, objectMapper);
    }

    @Test
    void record_savesCorrectFields() throws Exception {
        UUID processId = UUID.randomUUID();
        Map<String, Object> payload = Map.of("definitionName", "TEST_FLOW");
        when(objectMapper.writeValueAsString(payload)).thenReturn("{\"definitionName\":\"TEST_FLOW\"}");

        auditLogService.record(processId, AuditEventType.PROCESS_STARTED, "STEP_1",
                null, ProcessStatus.RUNNING, payload);

        ArgumentCaptor<ProcessAuditLog> captor = ArgumentCaptor.forClass(ProcessAuditLog.class);
        verify(auditLogRepository).save(captor.capture());

        ProcessAuditLog saved = captor.getValue();
        assertThat(saved.getProcessId()).isEqualTo(processId);
        assertThat(saved.getEventType()).isEqualTo(AuditEventType.PROCESS_STARTED);
        assertThat(saved.getStepName()).isEqualTo("STEP_1");
        assertThat(saved.getFromStatus()).isNull();
        assertThat(saved.getToStatus()).isEqualTo("RUNNING");
        assertThat(saved.getPayload()).isEqualTo("{\"definitionName\":\"TEST_FLOW\"}");
        assertThat(saved.getOccurredAt()).isNotNull();
    }

    @Test
    void record_nullPayload_noNPE() throws Exception {
        UUID processId = UUID.randomUUID();

        auditLogService.record(processId, AuditEventType.PROCESS_COMPLETED, "COMPLETED",
                ProcessStatus.RUNNING, ProcessStatus.COMPLETED, null);

        verify(auditLogRepository).save(any(ProcessAuditLog.class));
        verify(objectMapper, never()).writeValueAsString(any());
    }

    @Test
    void record_unserializablePayload_swallowsAndSavesNullPayload() throws Exception {
        UUID processId = UUID.randomUUID();
        Object badPayload = new Object();
        when(objectMapper.writeValueAsString(badPayload)).thenThrow(new JsonProcessingException("boom") {});

        auditLogService.record(processId, AuditEventType.STEP_TRANSITION, "STEP_2",
                ProcessStatus.RUNNING, ProcessStatus.RUNNING, badPayload);

        ArgumentCaptor<ProcessAuditLog> captor = ArgumentCaptor.forClass(ProcessAuditLog.class);
        verify(auditLogRepository).save(captor.capture());
        assertThat(captor.getValue().getPayload()).isNull();
    }
}
