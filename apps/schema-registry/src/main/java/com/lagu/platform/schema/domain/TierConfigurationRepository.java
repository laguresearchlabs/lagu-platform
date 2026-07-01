package com.lagu.platform.schema.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TierConfigurationRepository extends JpaRepository<TierConfiguration, UUID> {
    Optional<TierConfiguration> findByTierNameAndListingTypeIsNull(String tierName);
    Optional<TierConfiguration> findByTierNameAndListingType(String tierName, String listingType);
    List<TierConfiguration> findByActiveTrueOrderByTierName();
}
