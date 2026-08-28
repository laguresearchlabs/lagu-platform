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

    /**
     * The org's owner, for services that need one concrete person to address (booking-service
     * resolving who to notify about a new inquiry).
     *
     * `findFirst...OrderByJoinedAtAsc` rather than a plain single-result finder: nothing in the
     * schema stops an org having two OWNER rows, and throwing IncorrectResultSizeDataAccessException
     * at a notification lookup would be a poor trade. Oldest wins, which is the founding owner.
     */
    Optional<VendorMember> findFirstByTenantIdAndRoleAndStatusOrderByJoinedAtAsc(
            UUID tenantId, String role, String status);
}
