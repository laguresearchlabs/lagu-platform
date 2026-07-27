package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldGroupRepository extends JpaRepository<FieldGroup, UUID> {
    Optional<FieldGroup> findByNameAndTenantIdIsNull(String name);
    Optional<FieldGroup> findByNameAndTenantId(String name, UUID tenantId);
    List<FieldGroup> findByTenantIdIsNullAndActiveTrue();

    @Query("SELECT fg FROM FieldGroup fg LEFT JOIN FETCH fg.entries e LEFT JOIN FETCH e.field WHERE fg.name = :name AND fg.tenantId IS NULL")
    Optional<FieldGroup> findByNameWithFieldsAndTenantIdIsNull(String name);
}
