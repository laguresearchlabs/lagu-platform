package com.lagu.platform.metadata.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ObjectTypeDefinitionRepository extends JpaRepository<ObjectTypeDefinition, UUID> {

    Optional<ObjectTypeDefinition> findByNameAndOrgId(String name, UUID orgId);

    Optional<ObjectTypeDefinition> findByNameAndOrgIdIsNull(String name);

    @Query("SELECT o FROM ObjectTypeDefinition o WHERE (o.orgId = :orgId OR o.orgId IS NULL) AND o.active = true ORDER BY o.name")
    List<ObjectTypeDefinition> findAllActiveForOrg(UUID orgId);

    @Query("SELECT o FROM ObjectTypeDefinition o WHERE o.orgId IS NULL AND o.active = true ORDER BY o.name")
    List<ObjectTypeDefinition> findAllPlatformLevel();

    @Query("""
        SELECT o FROM ObjectTypeDefinition o
        WHERE (o.name = :name) AND (o.orgId = :orgId OR o.orgId IS NULL)
        ORDER BY o.orgId DESC NULLS LAST
        """)
    List<ObjectTypeDefinition> findByNameForOrg(String name, UUID orgId);
}
