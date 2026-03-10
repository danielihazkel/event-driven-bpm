package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

@Slf4j
@Service
public class StepTimeoutService {

    private final ProcessInstanceRepository repository;
    private final long stepTimeoutMinutes;
    private final AuditLogService auditLogService;

    public StepTimeoutService(ProcessInstanceRepository repository,
                              @Value("${edoe.orchestrator.step-timeout-minutes:30}") long stepTimeoutMinutes,
                              AuditLogService auditLogService) {
        this.repository = repository;
        this.stepTimeoutMinutes = stepTimeoutMinutes;
        this.auditLogService = auditLogService;
    }

    @Scheduled(fixedDelayString = "${edoe.orchestrator.stalled-check-interval-ms:60000}")
    @Transactional
    public void detectStalledProcesses() {
        LocalDateTime threshold = LocalDateTime.now().minusMinutes(stepTimeoutMinutes);
        List<ProcessInstance> stalled = repository.findStalledProcesses(threshold);
        for (ProcessInstance instance : stalled) {
            instance.setStatus(ProcessStatus.STALLED);
            auditLogService.record(instance.getId(), AuditEventType.PROCESS_STALLED, instance.getCurrentStep(),
                    ProcessStatus.RUNNING, ProcessStatus.STALLED, null);
            log.warn("Process {} marked STALLED — step={} stepStartedAt={}",
                    instance.getId(), instance.getCurrentStep(), instance.getStepStartedAt());
        }
    }
}
