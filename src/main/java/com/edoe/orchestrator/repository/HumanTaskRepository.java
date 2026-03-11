package com.edoe.orchestrator.repository;

import com.edoe.orchestrator.entity.HumanTask;
import com.edoe.orchestrator.entity.HumanTaskStatus;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface HumanTaskRepository extends JpaRepository<HumanTask, UUID> {

    List<HumanTask> findByStatus(HumanTaskStatus status);

    List<HumanTask> findByProcessInstanceId(UUID processInstanceId);

    List<HumanTask> findByAssigneeAndStatus(String assignee, HumanTaskStatus status);

    List<HumanTask> findByStatusAndProcessDefinitionName(HumanTaskStatus status, String processDefinitionName);
}
