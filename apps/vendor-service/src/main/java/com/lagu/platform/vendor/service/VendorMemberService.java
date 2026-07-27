package com.lagu.platform.vendor.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.membership.MembershipPolicy;
import com.lagu.platform.vendor.domain.VendorMember;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import com.lagu.platform.vendor.domain.VendorProfile;
import com.lagu.platform.vendor.domain.VendorProfileRepository;
import com.lagu.platform.vendor.dto.InviteVendorMemberRequest;
import com.lagu.platform.vendor.dto.UpdateVendorMemberRoleRequest;
import com.lagu.platform.vendor.dto.VendorMemberResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Set;
import java.util.UUID;

/**
 * Team membership for a vendor org — a user can be a VendorMember of many vendor orgs at once
 * (UNIQUE(tenant_id, user_id), not UNIQUE(user_id)). Authorization here is therefore local, never
 * derived from the caller's JWT tenantId claim, which only ever reflects the single org a request
 * targets — see VendorController/VendorService for the matching behavior on the vendor-facing side.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VendorMemberService {

    private static final List<String> VALID_ROLES = List.of("OWNER", "ADMIN", "MEMBER");
    private static final Set<String> MANAGER_ROLES = Set.of("OWNER", "ADMIN");

    private final VendorProfileRepository profileRepo;
    private final VendorMemberRepository  memberRepo;

    public List<VendorMemberResponse> list(UUID vendorId, UUID requesterId) {
        VendorProfile profile = requireProfile(vendorId);
        requireMember(profile, requesterId);
        return memberRepo.findByTenantId(profile.getTenantId()).stream()
                .filter(VendorMember::isActive)
                .map(this::toResponse).toList();
    }

    @Transactional
    public VendorMemberResponse invite(UUID vendorId, UUID requesterId, InviteVendorMemberRequest req) {
        VendorProfile profile = requireProfile(vendorId);
        requireManager(profile, requesterId);
        validateRole(req.getRole());

        VendorMember member = memberRepo.findByTenantIdAndUserId(profile.getTenantId(), req.getUserId())
                .orElseGet(() -> {
                    VendorMember m = new VendorMember();
                    m.setTenantId(profile.getTenantId());
                    m.setUserId(req.getUserId());
                    return m;
                });

        if (member.getId() != null && member.isActive()) {
            throw new ValidationException("User is already a member of this vendor org");
        }

        member.setRole(req.getRole() != null ? req.getRole().toUpperCase() : "MEMBER");
        member.setStatus("ACTIVE");
        member.setInvitedBy(requesterId);
        member.setRemovedBy(null);
        member.setRemovedAt(null);
        memberRepo.save(member);

        log.info("User {} invited {} to vendor {} as {}", requesterId, req.getUserId(), vendorId, member.getRole());
        return toResponse(member);
    }

    @Transactional
    public VendorMemberResponse updateRole(UUID vendorId, UUID requesterId, UUID targetUserId,
                                           UpdateVendorMemberRoleRequest req) {
        VendorProfile profile = requireProfile(vendorId);
        requireManager(profile, requesterId);
        validateRole(req.getRole());
        MembershipPolicy.requireNotSelf(requesterId, targetUserId);

        VendorMember member = memberRepo.findByTenantIdAndUserIdAndStatus(profile.getTenantId(), targetUserId, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException("VendorMember", targetUserId.toString()));

        String newRole = req.getRole().toUpperCase();
        List<VendorMember> allMembers = memberRepo.findByTenantId(profile.getTenantId());
        MembershipPolicy.requireManagerRemainsAfterMutation(allMembers, targetUserId, newRole, MANAGER_ROLES);

        member.setRole(newRole);
        member.setUpdatedBy(requesterId);
        member.setUpdatedAt(Instant.now());
        return toResponse(memberRepo.save(member));
    }

    @Transactional
    public void remove(UUID vendorId, UUID requesterId, UUID targetUserId) {
        VendorProfile profile = requireProfile(vendorId);
        requireManager(profile, requesterId);
        MembershipPolicy.requireNotSelf(requesterId, targetUserId);

        if (targetUserId.equals(profile.getOwnerUserId())) {
            throw new ValidationException("Cannot remove the vendor's registering owner");
        }
        VendorMember member = memberRepo.findByTenantIdAndUserIdAndStatus(profile.getTenantId(), targetUserId, "ACTIVE")
                .orElseThrow(() -> new ResourceNotFoundException("VendorMember", targetUserId.toString()));

        List<VendorMember> allMembers = memberRepo.findByTenantId(profile.getTenantId());
        MembershipPolicy.requireManagerRemainsAfterMutation(allMembers, targetUserId, null, MANAGER_ROLES);

        member.setStatus("REMOVED");
        member.setRemovedBy(requesterId);
        member.setRemovedAt(Instant.now());
        memberRepo.save(member);
    }

    // ── helpers (also used by VendorService for its own membership-gated endpoints) ───────────

    private void validateRole(String role) {
        if (role != null && !VALID_ROLES.contains(role.toUpperCase())) {
            throw new ValidationException("Invalid role: " + role);
        }
    }

    /** vendorId here is VendorProfile.id — the vendor's own PK doubles as its platform tenantId. */
    private VendorProfile requireProfile(UUID vendorId) {
        return profileRepo.findById(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId.toString()));
    }

    private VendorMember requireMember(VendorProfile profile, UUID userId) {
        return memberRepo.findByTenantIdAndUserIdAndStatus(profile.getTenantId(), userId, "ACTIVE")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this vendor org"));
    }

    private VendorMember requireManager(VendorProfile profile, UUID userId) {
        VendorMember member = requireMember(profile, userId);
        if (!"OWNER".equals(member.getRole()) && !"ADMIN".equals(member.getRole())) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "OWNER or ADMIN role required");
        }
        return member;
    }

    private VendorMemberResponse toResponse(VendorMember m) {
        return VendorMemberResponse.builder()
                .id(m.getId()).userId(m.getUserId()).role(m.getRole())
                .invitedBy(m.getInvitedBy())
                .joinedAt(m.getJoinedAt() != null ? m.getJoinedAt().atOffset(java.time.ZoneOffset.UTC) : null)
                .build();
    }
}
