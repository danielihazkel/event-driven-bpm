package com.edoe.orchestrator.repository;

import com.edoe.orchestrator.entity.WebhookSubscription;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface WebhookSubscriptionRepository extends JpaRepository<WebhookSubscription, UUID> {

    @Query("""
           SELECT w FROM WebhookSubscription w
           WHERE w.active = true
           AND (w.processDefinitionName IS NULL
                OR w.processDefinitionName = :definitionName)
           """)
    List<WebhookSubscription> findActiveForDefinition(@Param("definitionName") String definitionName);
}
