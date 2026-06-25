package com.lagu.platform.metadata.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface EntityDefinitionRepository extends JpaRepository<EntityDefinition, UUID> {

    Optional<EntityDefinition> findByNameAndOrgId(String name, UUID orgId);

    Optional<EntityDefinition> findByNameAndOrgIdIsNull(String name);

    @Query("SELECT e FROM EntityDefinition e WHERE (e.orgId = :orgId OR e.orgId IS NULL) AND e.active = true ORDER BY e.name")
    List<EntityDefinition> findAllActiveForOrg(UUID orgId);

    @Query("SELECT e FROM EntityDefinition e WHERE e.orgId IS NULL AND e.active = true ORDER BY e.name")
    List<EntityDefinition> findAllPlatformLevel();
}
