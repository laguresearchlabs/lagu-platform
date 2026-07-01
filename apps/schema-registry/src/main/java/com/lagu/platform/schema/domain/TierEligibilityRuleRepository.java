package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.UUID;

public interface TierEligibilityRuleRepository extends JpaRepository<TierEligibilityRule, UUID> {
    List<TierEligibilityRule> findByListingTypeAndActiveTrueOrderByDisplayOrder(String listingType);
    List<TierEligibilityRule> findByListingTypeAndTierAndActiveTrueOrderByDisplayOrder(String listingType, String tier);
}
