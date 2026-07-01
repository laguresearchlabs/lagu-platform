package com.lagu.platform.vendor.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorMemberRepository extends JpaRepository<VendorMember, UUID> {
    List<VendorMember> findByOrgId(UUID orgId);
    Optional<VendorMember> findByOrgIdAndUserId(UUID orgId, UUID userId);
    boolean existsByOrgIdAndUserId(UUID orgId, UUID userId);
}
