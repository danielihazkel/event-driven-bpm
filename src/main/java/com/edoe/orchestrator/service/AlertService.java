package com.edoe.orchestrator.service;

import java.util.UUID;

public interface AlertService {
    void compensationFailed(UUID processId, String step, String reason);
}
