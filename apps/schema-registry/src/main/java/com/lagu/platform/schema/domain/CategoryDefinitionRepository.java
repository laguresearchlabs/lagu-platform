package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryDefinitionRepository extends JpaRepository<CategoryDefinition, UUID> {
    Optional<CategoryDefinition> findBySlugAndOrgIdIsNull(String slug);
    List<CategoryDefinition> findByParentIsNullAndActiveTrue();
    List<CategoryDefinition> findByParentIdAndActiveTrue(UUID parentId);
    List<CategoryDefinition> findByListingTypeAndActiveTrue(String listingType);
}
