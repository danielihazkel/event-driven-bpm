package com.edoe.orchestrator.service;

import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThatNoException;

class Slf4jAlertServiceTest {

    private final Slf4jAlertService alertService = new Slf4jAlertService();

    @Test
    void compensationFailed_logsWithoutThrowing() {
        UUID processId = UUID.randomUUID();
        assertThatNoException().isThrownBy(() ->
                alertService.compensationFailed(processId, "COMPENSATE_PAYMENT", "step failed after 3 retries"));
    }

    @Test
    void compensationFailed_withNullReason_logsWithoutThrowing() {
        UUID processId = UUID.randomUUID();
        assertThatNoException().isThrownBy(() ->
                alertService.compensationFailed(processId, "COMPENSATE_STEP", null));
    }
}
