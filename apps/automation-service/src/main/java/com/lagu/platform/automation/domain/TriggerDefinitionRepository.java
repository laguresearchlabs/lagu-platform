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

    /** Returns active triggers matching eventType for the org (including platform-level where org_id IS NULL). */
    @Query("""
        SELECT t FROM TriggerDefinition t
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
        SELECT t FROM TriggerDefinition t
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
}
