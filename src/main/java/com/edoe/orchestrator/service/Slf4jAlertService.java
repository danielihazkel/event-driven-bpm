package com.edoe.orchestrator.service;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import java.util.UUID;

@Service
public class Slf4jAlertService implements AlertService {
    private static final Logger log = LoggerFactory.getLogger("alerts");

    @Override
    public void compensationFailed(UUID processId, String step, String reason) {
        log.error("COMPENSATION_FAILED processId={} step={} reason={}", processId, step, reason);
    }
}
