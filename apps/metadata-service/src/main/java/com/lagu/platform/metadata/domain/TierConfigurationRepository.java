package com.lagu.platform.metadata.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TierConfigurationRepository extends JpaRepository<TierConfiguration, UUID> {

    Optional<TierConfiguration> findByTierNameAndObjectTypeIsNull(String tierName);

    /** Object-type-specific config wins over generic (null objectType). */
    @Query("SELECT t FROM TierConfiguration t WHERE t.tierName = :tierName " +
           "AND (t.objectType = :objectType OR t.objectType IS NULL) " +
           "AND t.active = true ORDER BY t.objectType DESC NULLS LAST")
    List<TierConfiguration> findByTierNameForObjectType(@Param("tierName") String tierName,
                                                        @Param("objectType") String objectType);

    List<TierConfiguration> findByActiveTrue();
}
