package com.lagu.platform.vendor.security;

import com.lagu.platform.membership.AbstractMembershipPermissionEvaluator;
import com.lagu.platform.membership.MembershipRecord;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Authorizes VendorMemberController's invite/updateRole/remove endpoints against
 * VendorMember directly. {@code {tenantId}} in those routes already IS VendorProfile.id
 * (vendor-service's own PK doubles as the platform org id — same pattern as event-service's
 * Event.id/Event.tenantId collapse; see EventMembershipPermissionEvaluator).
 */
@Component
@RequiredArgsConstructor
public class VendorMembershipPermissionEvaluator extends AbstractMembershipPermissionEvaluator {

    private final VendorMemberRepository memberRepo;

    @Override
    protected String supportedResource() {
        return "VENDOR_MEMBER";
    }

    @Override
    protected Set<String> gateRoles() {
        return Set.of("OWNER", "ADMIN");
    }

    @Override
    protected String pathIdVariable() {
        return "tenantId";
    }

    @Override
    protected Optional<? extends MembershipRecord> findMembership(UUID tenantId, UUID userId) {
        return memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE");
    }
}
