package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingTypeDefinitionRepository extends JpaRepository<ListingTypeDefinition, UUID> {
    Optional<ListingTypeDefinition> findByNameAndOrgIdIsNull(String name);
    Optional<ListingTypeDefinition> findByNameAndOrgId(String name, UUID orgId);
    List<ListingTypeDefinition> findByOrgIdIsNullAndActiveTrue();
    List<ListingTypeDefinition> findByConsumerSearchableTrueAndActiveTrue();

    @Query("""
        SELECT ltd FROM ListingTypeDefinition ltd
        LEFT JOIN FETCH ltd.sections s
        LEFT JOIN FETCH s.fieldGroup fg
        WHERE ltd.name = :name AND ltd.orgId IS NULL AND ltd.active = true
        """)
    Optional<ListingTypeDefinition> findByNameWithSectionsAndOrgIdIsNull(String name);
}
