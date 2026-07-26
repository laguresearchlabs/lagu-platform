package com.lagu.platform.vendor.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
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

import java.util.List;
import java.util.UUID;

/**
 * Team membership for a vendor org — a user can be a VendorMember of many vendor orgs at once
 * (UNIQUE(org_id, user_id), not UNIQUE(user_id)), unlike IAM's User.platformOrgId (a single
 * scalar). Authorization here is therefore local, never derived from the caller's JWT orgId
 * claim — see VendorController/VendorService for the matching change on the vendor-facing side.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class VendorMemberService {

    private static final List<String> VALID_ROLES = List.of("OWNER", "ADMIN", "MEMBER");

    private final VendorProfileRepository profileRepo;
    private final VendorMemberRepository  memberRepo;

    public List<VendorMemberResponse> list(UUID vendorId, UUID requesterId) {
        VendorProfile profile = requireProfile(vendorId);
        requireMember(profile, requesterId);
        return memberRepo.findByOrgId(profile.getOrgId()).stream().map(this::toResponse).toList();
    }

    @Transactional
    public VendorMemberResponse invite(UUID vendorId, UUID requesterId, InviteVendorMemberRequest req) {
        VendorProfile profile = requireProfile(vendorId);
        requireManager(profile, requesterId);
        validateRole(req.getRole());

        if (memberRepo.existsByOrgIdAndUserId(profile.getOrgId(), req.getUserId())) {
            throw new ValidationException("User is already a member of this vendor org");
        }

        VendorMember member = new VendorMember();
        member.setOrgId(profile.getOrgId());
        member.setUserId(req.getUserId());
        member.setRole(req.getRole() != null ? req.getRole().toUpperCase() : "MEMBER");
        member.setInvitedBy(requesterId);
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

        VendorMember member = memberRepo.findByOrgIdAndUserId(profile.getOrgId(), targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorMember", targetUserId.toString()));
        member.setRole(req.getRole().toUpperCase());
        return toResponse(memberRepo.save(member));
    }

    @Transactional
    public void remove(UUID vendorId, UUID requesterId, UUID targetUserId) {
        VendorProfile profile = requireProfile(vendorId);
        requireManager(profile, requesterId);

        if (targetUserId.equals(profile.getOwnerUserId())) {
            throw new ValidationException("Cannot remove the vendor's registering owner");
        }
        VendorMember member = memberRepo.findByOrgIdAndUserId(profile.getOrgId(), targetUserId)
                .orElseThrow(() -> new ResourceNotFoundException("VendorMember", targetUserId.toString()));
        memberRepo.delete(member);
    }

    // ── helpers (also used by VendorService for its own membership-gated endpoints) ───────────

    private void validateRole(String role) {
        if (role != null && !VALID_ROLES.contains(role.toUpperCase())) {
            throw new ValidationException("Invalid role: " + role);
        }
    }

    /** vendorId here is VendorProfile.orgId — the platform orgId doubles as the public vendorId. */
    private VendorProfile requireProfile(UUID vendorId) {
        return profileRepo.findByOrgId(vendorId)
                .orElseThrow(() -> new ResourceNotFoundException("Vendor", vendorId.toString()));
    }

    private VendorMember requireMember(VendorProfile profile, UUID userId) {
        return memberRepo.findByOrgIdAndUserId(profile.getOrgId(), userId)
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
                .invitedBy(m.getInvitedBy()).joinedAt(m.getJoinedAt())
                .build();
    }
}
