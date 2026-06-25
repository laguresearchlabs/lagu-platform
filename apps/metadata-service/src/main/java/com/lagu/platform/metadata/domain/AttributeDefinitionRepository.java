package com.lagu.platform.metadata.domain;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface AttributeDefinitionRepository extends JpaRepository<AttributeDefinition, UUID> {

    Optional<AttributeDefinition> findByNameAndOrgId(String name, UUID orgId);

    Optional<AttributeDefinition> findByNameAndOrgIdIsNull(String name);

    @Query("SELECT a FROM AttributeDefinition a WHERE (a.orgId = :orgId OR a.orgId IS NULL) AND a.active = true ORDER BY a.name")
    List<AttributeDefinition> findAllActiveForOrg(UUID orgId);

    @Query("SELECT a FROM AttributeDefinition a WHERE (a.orgId = :orgId OR a.orgId IS NULL) AND a.active = true")
    Page<AttributeDefinition> findAllActiveForOrg(UUID orgId, Pageable pageable);

    @Query("SELECT a FROM AttributeDefinition a WHERE a.orgId IS NULL AND a.active = true ORDER BY a.name")
    List<AttributeDefinition> findAllPlatformLevel();

    boolean existsByNameAndOrgId(String name, UUID orgId);
}
