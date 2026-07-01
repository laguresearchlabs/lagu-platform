package com.lagu.platform.metadata.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentTypeDefinitionRepository extends JpaRepository<DocumentTypeDefinition, UUID> {

    Optional<DocumentTypeDefinition> findByCodeAndOrgIdIsNull(String code);

    /** Platform-level types (no org) for a given object type, ordered by displayOrder. */
    @Query("SELECT d FROM DocumentTypeDefinition d WHERE d.orgId IS NULL " +
           "AND (d.objectType IS NULL OR d.objectType = :objectType) " +
           "AND d.active = true ORDER BY d.displayOrder ASC")
    List<DocumentTypeDefinition> findPlatformByObjectType(@Param("objectType") String objectType);

    /** All active types for an org (org-level overrides + platform-level fallbacks). */
    @Query("SELECT d FROM DocumentTypeDefinition d WHERE (d.orgId = :orgId OR d.orgId IS NULL) " +
           "AND (d.objectType IS NULL OR d.objectType = :objectType) " +
           "AND d.active = true ORDER BY d.orgId DESC NULLS LAST, d.displayOrder ASC")
    List<DocumentTypeDefinition> findForOrgAndObjectType(@Param("orgId") UUID orgId,
                                                         @Param("objectType") String objectType);

    List<DocumentTypeDefinition> findByOrgIdIsNullAndActiveTrue();
}
