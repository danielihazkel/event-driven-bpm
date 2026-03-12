package com.edoe.orchestrator;

import com.edoe.orchestrator.service.OutboxPublisherService;
import com.edoe.orchestrator.service.StepTimeoutService;
import com.edoe.orchestrator.service.TimerService;
import com.edoe.orchestrator.service.WebhookDispatchService;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.TestPropertySource;

@SpringBootTest
@TestPropertySource(properties = {
        "spring.datasource.url=jdbc:h2:mem:testdb;DB_CLOSE_DELAY=-1",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.jpa.database-platform=org.hibernate.dialect.H2Dialect",
        "spring.jpa.properties.hibernate.dialect=org.hibernate.dialect.H2Dialect",
        "spring.kafka.bootstrap-servers=localhost:9999",
        "spring.sql.init.mode=always",
        "edoe.orchestrator.jwt.secret=dGhpcy1pcy1hLXRlc3Qtc2VjcmV0LWtleS0tLS0tLS0tLS0="
})
public class OrchestratorApplicationTests {

    @MockBean
    private KafkaAdmin kafkaAdmin;

    @MockBean
    @SuppressWarnings("rawtypes")
    private KafkaTemplate kafkaTemplate;

    @MockBean
    private OutboxPublisherService outboxPublisherService;

    @MockBean
    private StepTimeoutService stepTimeoutService;

    @MockBean
    private WebhookDispatchService webhookDispatchService;

    @MockBean
    private TimerService timerService;

    @Test
    void contextLoads() {
    }
}
