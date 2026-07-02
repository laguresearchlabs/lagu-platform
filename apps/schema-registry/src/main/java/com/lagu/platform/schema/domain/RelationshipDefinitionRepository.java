package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RelationshipDefinitionRepository extends JpaRepository<RelationshipDefinition, UUID> {

    Optional<RelationshipDefinition> findByNameAndOrgIdIsNull(String name);

    Optional<RelationshipDefinition> findByNameAndOrgId(String name, UUID orgId);

    @Query("SELECT r FROM RelationshipDefinition r WHERE r.active = true AND (r.orgId IS NULL OR r.orgId = :orgId) ORDER BY r.name")
    List<RelationshipDefinition> findAllForOrg(UUID orgId);

    @Query("SELECT r FROM RelationshipDefinition r WHERE r.active = true AND r.orgId IS NULL ORDER BY r.name")
    List<RelationshipDefinition> findAllPlatformLevel();
}
