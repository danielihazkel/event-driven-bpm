package com.edoe.orchestrator.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;

@Configuration
public class KafkaTopicConfig {

    public static final String ORCHESTRATOR_COMMANDS_TOPIC = "orchestrator-commands";
    public static final String WORKER_EVENTS_TOPIC = "worker-events";

    @Bean
    public NewTopic orchestratorCommandsTopic() {
        return TopicBuilder.name(ORCHESTRATOR_COMMANDS_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic workerEventsTopic() {
        return TopicBuilder.name(WORKER_EVENTS_TOPIC)
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic workerEventsDltTopic() {
        return TopicBuilder.name(WORKER_EVENTS_TOPIC + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }

    @Bean
    public NewTopic orchestratorCommandsDltTopic() {
        return TopicBuilder.name(ORCHESTRATOR_COMMANDS_TOPIC + ".DLT")
                .partitions(1)
                .replicas(1)
                .build();
    }
}
