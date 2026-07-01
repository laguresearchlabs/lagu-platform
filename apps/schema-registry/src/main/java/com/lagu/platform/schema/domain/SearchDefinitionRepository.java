package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SearchDefinitionRepository extends JpaRepository<SearchDefinition, UUID> {
    Optional<SearchDefinition> findByListingType(String listingType);
}
