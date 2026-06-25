package com.lagu.platform.metadata.domain.role;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PermissionDefinitionRepository extends JpaRepository<PermissionDefinition, UUID> {

    List<PermissionDefinition> findByRoleId(UUID roleId);

    Optional<PermissionDefinition> findByResourceTypeAndActionAndRoleId(
            String resourceType, String action, UUID roleId);

    @Query("SELECT p FROM PermissionDefinition p WHERE p.role.id IN :roleIds AND (p.resourceType = :resource OR p.resourceType = '*')")
    List<PermissionDefinition> findByRoleIdsAndResource(List<UUID> roleIds, String resource);
}
