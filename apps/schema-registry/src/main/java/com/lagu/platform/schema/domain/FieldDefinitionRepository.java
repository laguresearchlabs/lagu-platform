package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, UUID> {
    Optional<FieldDefinition> findByNameAndTenantIdIsNull(String name);
    Optional<FieldDefinition> findByNameAndTenantId(String name, UUID tenantId);
    List<FieldDefinition> findByTenantIdIsNullAndActiveTrue();
    List<FieldDefinition> findByTenantIdAndActiveTrue(UUID tenantId);
}
