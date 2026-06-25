package com.lagu.platform.metadata.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface RoleDefinitionRepository extends JpaRepository<RoleDefinition, UUID> {

    // Platform-level roles (org_id IS NULL) + org-scoped roles
    @Query("SELECT r FROM RoleDefinition r WHERE r.active = true AND (r.orgId IS NULL OR r.orgId = :orgId) ORDER BY r.roleLevel, r.name")
    List<RoleDefinition> findAllForOrg(UUID orgId);

    @Query("SELECT r FROM RoleDefinition r WHERE r.active = true AND r.orgId IS NULL ORDER BY r.name")
    List<RoleDefinition> findAllPlatformLevel();

    Optional<RoleDefinition> findByNameAndOrgIdIsNull(String name);

    boolean existsByNameAndOrgId(String name, UUID orgId);
}
