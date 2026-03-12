package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.AuditEventType;
import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * Polls for {@code SCHEDULED} process instances whose {@code wakeAt} timestamp
 * has passed and dispatches the pending step command via the transactional outbox.
 *
 * <p>Scheduling interval is configurable via
 * {@code edoe.orchestrator.timer-poll-interval-ms} (default 5 000 ms).</p>
 */
@Slf4j
@RequiredArgsConstructor
@Service
public class TimerService {

    private final ProcessInstanceRepository instanceRepository;
    private final OutboxEventRepository outboxRepository;
    private final AuditLogService auditLogService;

    @Scheduled(fixedDelayString = "${edoe.orchestrator.timer-poll-interval-ms:5000}")
    @SchedulerLock(name = "wakeExpiredTimers", lockAtLeastFor = "PT4S", lockAtMostFor = "PT2M")
    @Transactional
    public void wakeExpiredTimers() {
        List<ProcessInstance> due = instanceRepository
                .findByStatusAndWakeAtLessThanEqual(ProcessStatus.SCHEDULED, LocalDateTime.now());

        if (due.isEmpty()) {
            return;
        }

        log.debug("TimerService: {} scheduled process(es) ready to wake", due.size());

        for (ProcessInstance instance : due) {
            instance.setStatus(ProcessStatus.RUNNING);
            instance.setWakeAt(null);
            instance.setStepStartedAt(LocalDateTime.now());
            instanceRepository.saveAndFlush(instance);
            outboxRepository.save(new OutboxEvent(
                    instance.getId().toString(),
                    instance.getCurrentStep(),
                    instance.getContextData()));
            auditLogService.record(instance.getId(), AuditEventType.PROCESS_TIMER_FIRED, instance.getCurrentStep(),
                    ProcessStatus.SCHEDULED, ProcessStatus.RUNNING, null);
            log.info("Process {} woken from timer, dispatching step {}", instance.getId(), instance.getCurrentStep());
        }
    }
}
