package com.lagu.platform.metadata.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CategoryDefinitionRepository extends JpaRepository<CategoryDefinition, UUID> {

    Optional<CategoryDefinition> findBySlugAndOrgIdIsNull(String slug);

    List<CategoryDefinition> findByParentIsNullAndOrgIdIsNullAndActiveTrue();

    @Query("SELECT c FROM CategoryDefinition c WHERE c.objectType = :objectType " +
           "AND (c.orgId IS NULL OR c.orgId = :orgId) AND c.active = true " +
           "ORDER BY c.displayOrder ASC")
    List<CategoryDefinition> findByObjectTypeForOrg(@Param("objectType") String objectType,
                                                    @Param("orgId") UUID orgId);
}
