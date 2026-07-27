package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ListingTypeDefinitionRepository extends JpaRepository<ListingTypeDefinition, UUID> {
    Optional<ListingTypeDefinition> findByNameAndTenantIdIsNull(String name);
    Optional<ListingTypeDefinition> findByNameAndTenantId(String name, UUID tenantId);
    List<ListingTypeDefinition> findByTenantIdIsNullAndActiveTrue();
    List<ListingTypeDefinition> findByConsumerSearchableTrueAndActiveTrue();

    @Query("""
        SELECT ltd FROM ListingTypeDefinition ltd
        LEFT JOIN FETCH ltd.sections s
        LEFT JOIN FETCH s.fieldGroup fg
        WHERE ltd.name = :name AND ltd.tenantId IS NULL AND ltd.active = true
        """)
    Optional<ListingTypeDefinition> findByNameWithSectionsAndTenantIdIsNull(String name);
}
