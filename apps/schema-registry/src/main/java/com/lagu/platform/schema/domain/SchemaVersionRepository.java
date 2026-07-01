package com.lagu.platform.schema.domain;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface SchemaVersionRepository extends JpaRepository<SchemaVersion, UUID> {
    Optional<SchemaVersion> findByListingTypeAndVersion(String listingType, int version);
    List<SchemaVersion> findByListingTypeOrderByVersionDesc(String listingType, Pageable pageable);
    Optional<SchemaVersion> findTopByListingTypeOrderByVersionDesc(String listingType);
}
