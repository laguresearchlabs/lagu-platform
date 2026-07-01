package com.lagu.platform.vendor.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorProfileRepository extends JpaRepository<VendorProfile, UUID> {
    Optional<VendorProfile> findByOrgId(UUID orgId);
    Optional<VendorProfile> findByOwnerUserId(UUID userId);
    List<VendorProfile> findByStatus(String status);
}
