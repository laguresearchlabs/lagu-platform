package com.lagu.platform.vendor.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface VendorMemberRepository extends JpaRepository<VendorMember, UUID> {
    List<VendorMember> findByTenantId(UUID tenantId);
    Optional<VendorMember> findByTenantIdAndUserId(UUID tenantId, UUID userId);
    Optional<VendorMember> findByTenantIdAndUserIdAndStatus(UUID tenantId, UUID userId, String status);
    boolean existsByTenantIdAndUserId(UUID tenantId, UUID userId);
    List<VendorMember> findByUserId(UUID userId);
}
