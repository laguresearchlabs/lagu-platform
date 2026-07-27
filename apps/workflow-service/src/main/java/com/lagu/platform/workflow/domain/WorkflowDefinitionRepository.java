package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    /** Org-scoped takes priority over platform-level (tenant_id IS NULL). */
    @Query("""
            SELECT w FROM WorkflowDefinition w
            WHERE w.active = true AND w.objectType = :objectType
              AND (w.tenantId = :tenantId OR w.tenantId IS NULL)
            ORDER BY w.tenantId NULLS LAST
            """)
    List<WorkflowDefinition> findForObjectType(String objectType, UUID tenantId);

    List<WorkflowDefinition> findByActiveTrue();

    /** Org-scoped listing — see findForObjectType for the same "own org or platform-level" rule. */
    @Query("SELECT w FROM WorkflowDefinition w WHERE w.active = true AND (w.tenantId = :tenantId OR w.tenantId IS NULL)")
    List<WorkflowDefinition> findByActiveTrueAndTenantIdOrPlatformLevel(UUID tenantId);
}
