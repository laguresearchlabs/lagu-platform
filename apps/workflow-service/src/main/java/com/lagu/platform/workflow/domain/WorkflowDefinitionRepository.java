package com.lagu.platform.workflow.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface WorkflowDefinitionRepository extends JpaRepository<WorkflowDefinition, UUID> {

    /** Org-scoped takes priority over platform-level (org_id IS NULL). */
    @Query("""
            SELECT w FROM WorkflowDefinition w
            WHERE w.active = true AND w.objectType = :objectType
              AND (w.orgId = :orgId OR w.orgId IS NULL)
            ORDER BY w.orgId NULLS LAST
            """)
    List<WorkflowDefinition> findForObjectType(String objectType, UUID orgId);

    List<WorkflowDefinition> findByActiveTrue();
}
