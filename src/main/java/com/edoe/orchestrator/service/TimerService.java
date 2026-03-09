package com.edoe.orchestrator.service;

import com.edoe.orchestrator.entity.OutboxEvent;
import com.edoe.orchestrator.entity.ProcessInstance;
import com.edoe.orchestrator.entity.ProcessStatus;
import com.edoe.orchestrator.repository.OutboxEventRepository;
import com.edoe.orchestrator.repository.ProcessInstanceRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
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
@Service
public class TimerService {

    private static final Logger log = LoggerFactory.getLogger(TimerService.class);

    private final ProcessInstanceRepository instanceRepository;
    private final OutboxEventRepository outboxRepository;

    public TimerService(ProcessInstanceRepository instanceRepository, OutboxEventRepository outboxRepository) {
        this.instanceRepository = instanceRepository;
        this.outboxRepository = outboxRepository;
    }

    @Scheduled(fixedDelayString = "${edoe.orchestrator.timer-poll-interval-ms:5000}")
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
            log.info("Process {} woken from timer, dispatching step {}", instance.getId(), instance.getCurrentStep());
        }
    }
}
