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
     * tenant_id IS NULL) — with actions eagerly fetched (LEFT JOIN FETCH + DISTINCT to avoid
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
          AND (t.tenantId = :tenantId OR t.tenantId IS NULL)
          AND (t.objectType IS NULL OR t.objectType = :objectType)
        ORDER BY t.tenantId NULLS LAST
        """)
    List<TriggerDefinition> findActiveByEventAndType(
            @Param("eventType")  String eventType,
            @Param("tenantId")      UUID tenantId,
            @Param("objectType") String objectType);

    @Query("""
        SELECT DISTINCT t FROM TriggerDefinition t
        LEFT JOIN FETCH t.actions
        WHERE t.isActive = true
          AND t.eventType = :eventType
          AND (t.tenantId = :tenantId OR t.tenantId IS NULL)
        """)
    List<TriggerDefinition> findActiveByEvent(
            @Param("eventType") String eventType,
            @Param("tenantId")     UUID tenantId);

    @Query("SELECT t FROM TriggerDefinition t WHERE t.tenantId = :tenantId OR t.tenantId IS NULL")
    Page<TriggerDefinition> findAllForOrg(@Param("tenantId") UUID tenantId, Pageable pageable);

    @Query("SELECT t FROM TriggerDefinition t WHERE t.id = :id AND (t.tenantId = :tenantId OR t.tenantId IS NULL)")
    Optional<TriggerDefinition> findByIdAndOrg(@Param("id") UUID id, @Param("tenantId") UUID tenantId);

    Optional<TriggerDefinition> findByNameAndTenantIdIsNull(String name);
}
