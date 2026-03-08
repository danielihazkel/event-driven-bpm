package com.edoe.orchestrator.config;

import com.edoe.orchestrator.entity.ProcessDefinition;
import com.edoe.orchestrator.repository.ProcessDefinitionRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.stereotype.Component;

@Component
public class DataInitializer implements CommandLineRunner {

    private static final Logger log = LoggerFactory.getLogger(DataInitializer.class);

    private final ProcessDefinitionRepository definitionRepository;

    public DataInitializer(ProcessDefinitionRepository definitionRepository) {
        this.definitionRepository = definitionRepository;
    }

    @Override
    public void run(String... args) {
        if (definitionRepository.count() == 0) {
            ProcessDefinition defaultDef = new ProcessDefinition(
                    "DEFAULT_FLOW",
                    "STEP_1",
                    "{\"STEP_1_FINISHED\":\"STEP_2\",\"STEP_2_FINISHED\":\"COMPLETED\"}"
            );
            definitionRepository.save(defaultDef);
            log.info("Seeded DEFAULT_FLOW process definition");
        }
    }
}
