package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface DocumentRequirementRepository extends JpaRepository<DocumentRequirement, UUID> {
    Optional<DocumentRequirement> findByCodeAndOrgIdIsNull(String code);
    List<DocumentRequirement> findByListingTypeAndActiveTrueOrderByDisplayOrder(String listingType);
    List<DocumentRequirement> findByListingTypeIsNullAndActiveTrueOrderByDisplayOrder();
}
