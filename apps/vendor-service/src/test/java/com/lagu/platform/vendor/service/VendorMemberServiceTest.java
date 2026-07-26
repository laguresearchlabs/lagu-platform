package com.lagu.platform.vendor.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.vendor.domain.VendorMember;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import com.lagu.platform.vendor.domain.VendorProfile;
import com.lagu.platform.vendor.domain.VendorProfileRepository;
import com.lagu.platform.vendor.dto.InviteVendorMemberRequest;
import com.lagu.platform.vendor.dto.UpdateVendorMemberRoleRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * A user can belong to many vendor orgs at once (VendorMember's unique key is org_id+user_id,
 * not user_id alone) — these tests pin the authorization boundary that makes that safe: only
 * OWNER/ADMIN can manage membership, and the registering owner can never be removed.
 */
class VendorMemberServiceTest {

    private final VendorProfileRepository profileRepo = mock(VendorProfileRepository.class);
    private final VendorMemberRepository memberRepo = mock(VendorMemberRepository.class);
    private final VendorMemberService service = new VendorMemberService(profileRepo, memberRepo);

    private final UUID orgId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        VendorProfile profile = new VendorProfile();
        profile.setOrgId(orgId);
        profile.setOwnerUserId(ownerId);
        when(profileRepo.findByOrgId(orgId)).thenReturn(Optional.of(profile));
    }

    private VendorMember memberWithRole(UUID userId, String role) {
        VendorMember m = new VendorMember();
        m.setOrgId(orgId);
        m.setUserId(userId);
        m.setRole(role);
        return m;
    }

    @Test
    void inviteRejectedFromPlainMember() {
        UUID requester = UUID.randomUUID();
        when(memberRepo.findByOrgIdAndUserId(orgId, requester))
                .thenReturn(Optional.of(memberWithRole(requester, "MEMBER")));

        InviteVendorMemberRequest req = new InviteVendorMemberRequest();
        req.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.invite(orgId, requester, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void inviteRejectsAlreadyExistingMember() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "OWNER")));
        UUID target = UUID.randomUUID();
        when(memberRepo.existsByOrgIdAndUserId(orgId, target)).thenReturn(true);

        InviteVendorMemberRequest req = new InviteVendorMemberRequest();
        req.setUserId(target);

        assertThatThrownBy(() -> service.invite(orgId, ownerId, req))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void inviteSucceedsFromOwner() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "OWNER")));
        UUID target = UUID.randomUUID();
        when(memberRepo.existsByOrgIdAndUserId(orgId, target)).thenReturn(false);

        InviteVendorMemberRequest req = new InviteVendorMemberRequest();
        req.setUserId(target);
        req.setRole("MEMBER");

        service.invite(orgId, ownerId, req);

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(target) && "MEMBER".equals(m.getRole())));
    }

    @Test
    void removeRejectsRemovingTheRegisteringOwner() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "OWNER")));

        assertThatThrownBy(() -> service.remove(orgId, ownerId, ownerId))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).delete(any());
    }

    @Test
    void removeSucceedsForNonOwnerTarget() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "OWNER")));
        UUID target = UUID.randomUUID();
        VendorMember targetMember = memberWithRole(target, "MEMBER");
        when(memberRepo.findByOrgIdAndUserId(orgId, target)).thenReturn(Optional.of(targetMember));

        service.remove(orgId, ownerId, target);

        verify(memberRepo).delete(targetMember);
    }

    @Test
    void updateRoleSucceedsFromAdmin() {
        UUID admin = UUID.randomUUID();
        when(memberRepo.findByOrgIdAndUserId(orgId, admin))
                .thenReturn(Optional.of(memberWithRole(admin, "ADMIN")));
        UUID target = UUID.randomUUID();
        VendorMember targetMember = memberWithRole(target, "MEMBER");
        when(memberRepo.findByOrgIdAndUserId(orgId, target)).thenReturn(Optional.of(targetMember));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateVendorMemberRoleRequest req = new UpdateVendorMemberRoleRequest();
        req.setRole("ADMIN");

        var response = service.updateRole(orgId, admin, target, req);

        assertThat(response.getRole()).isEqualTo("ADMIN");
    }
}
