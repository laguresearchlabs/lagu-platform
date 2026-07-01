package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface FieldGroupRepository extends JpaRepository<FieldGroup, UUID> {
    Optional<FieldGroup> findByNameAndOrgIdIsNull(String name);
    Optional<FieldGroup> findByNameAndOrgId(String name, UUID orgId);
    List<FieldGroup> findByOrgIdIsNullAndActiveTrue();

    @Query("SELECT fg FROM FieldGroup fg LEFT JOIN FETCH fg.entries e LEFT JOIN FETCH e.field WHERE fg.name = :name AND fg.orgId IS NULL")
    Optional<FieldGroup> findByNameWithFieldsAndOrgIdIsNull(String name);
}
