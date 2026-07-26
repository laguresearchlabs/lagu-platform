package com.lagu.platform.automation.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TriggerDefinitionRepository extends JpaRepository<TriggerDefinition, UUID> {

    /**
     * Returns active triggers matching eventType for the org (including platform-level where
     * org_id IS NULL) — with actions eagerly fetched (LEFT JOIN FETCH + DISTINCT to avoid
     * duplicate rows per action). PlatformEventConsumer/EscalationScheduler pass these straight
     * into AutomationExecutor.execute(), which iterates trigger.getActions() outside any
     * transaction of its own; without eager fetch here, that access threw
     * LazyInitializationException every single time a trigger fired (open-in-view is
     * intentionally false — see TriggerDefinitionService for the same fix applied to the
     * read/write API paths).
     */
    @Query("""
        SELECT DISTINCT t FROM TriggerDefinition t
        LEFT JOIN FETCH t.actions
        WHERE t.isActive = true
          AND t.eventType = :eventType
          AND (t.orgId = :orgId OR t.orgId IS NULL)
          AND (t.objectType IS NULL OR t.objectType = :objectType)
        ORDER BY t.orgId NULLS LAST
        """)
    List<TriggerDefinition> findActiveByEventAndType(
            @Param("eventType")  String eventType,
            @Param("orgId")      UUID orgId,
            @Param("objectType") String objectType);

    @Query("""
        SELECT DISTINCT t FROM TriggerDefinition t
        LEFT JOIN FETCH t.actions
        WHERE t.isActive = true
          AND t.eventType = :eventType
          AND (t.orgId = :orgId OR t.orgId IS NULL)
        """)
    List<TriggerDefinition> findActiveByEvent(
            @Param("eventType") String eventType,
            @Param("orgId")     UUID orgId);

    @Query("SELECT t FROM TriggerDefinition t WHERE t.orgId = :orgId OR t.orgId IS NULL")
    Page<TriggerDefinition> findAllForOrg(@Param("orgId") UUID orgId, Pageable pageable);

    @Query("SELECT t FROM TriggerDefinition t WHERE t.id = :id AND (t.orgId = :orgId OR t.orgId IS NULL)")
    Optional<TriggerDefinition> findByIdAndOrg(@Param("id") UUID id, @Param("orgId") UUID orgId);

    Optional<TriggerDefinition> findByNameAndOrgIdIsNull(String name);
}
