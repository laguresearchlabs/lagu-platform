package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldDefinitionRepository extends JpaRepository<FieldDefinition, UUID> {
    Optional<FieldDefinition> findByNameAndOrgIdIsNull(String name);
    Optional<FieldDefinition> findByNameAndOrgId(String name, UUID orgId);
    List<FieldDefinition> findByOrgIdIsNullAndActiveTrue();
    List<FieldDefinition> findByOrgIdAndActiveTrue(UUID orgId);
}
