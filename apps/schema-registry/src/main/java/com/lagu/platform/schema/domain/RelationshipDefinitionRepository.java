package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelationshipDefinitionRepository extends JpaRepository<RelationshipDefinition, UUID> {

    Optional<RelationshipDefinition> findByNameAndTenantIdIsNull(String name);

    Optional<RelationshipDefinition> findByNameAndTenantId(String name, UUID tenantId);

    @Query("SELECT r FROM RelationshipDefinition r WHERE r.active = true AND (r.tenantId IS NULL OR r.tenantId = :tenantId) ORDER BY r.name")
    List<RelationshipDefinition> findAllForOrg(UUID tenantId);

    @Query("SELECT r FROM RelationshipDefinition r WHERE r.active = true AND r.tenantId IS NULL ORDER BY r.name")
    List<RelationshipDefinition> findAllPlatformLevel();
}
