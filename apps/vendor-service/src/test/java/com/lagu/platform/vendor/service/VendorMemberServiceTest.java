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

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

/**
 * A user can belong to many vendor orgs at once (VendorMember's unique key is tenant_id+user_id,
 * not user_id alone) — these tests pin the authorization boundary that makes that safe: only
 * OWNER/ADMIN can manage membership, the registering owner can never be removed, a manager
 * can't act on their own membership, and removal is a soft-delete (REMOVED status), not a hard
 * delete, so a removed user can later be re-invited.
 */
class VendorMemberServiceTest {

    private final VendorProfileRepository profileRepo = mock(VendorProfileRepository.class);
    private final VendorMemberRepository memberRepo = mock(VendorMemberRepository.class);
    private final VendorMemberService service = new VendorMemberService(profileRepo, memberRepo);

    private final UUID tenantId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    @BeforeEach
    void setUp() {
        VendorProfile profile = new VendorProfile();
        profile.setId(tenantId);
        profile.setOwnerUserId(ownerId);
        when(profileRepo.findById(tenantId)).thenReturn(Optional.of(profile));
    }

    private VendorMember memberWithRole(UUID userId, String role) {
        VendorMember m = new VendorMember();
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setRole(role);
        return m; // status defaults to ACTIVE via the field initializer
    }

    private void stubManager(UUID userId, String role) {
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE"))
                .thenReturn(Optional.of(memberWithRole(userId, role)));
    }

    // ── invite() ─────────────────────────────────────────────────────────────

    @Test
    void inviteRejectedFromPlainMember() {
        UUID requester = UUID.randomUUID();
        stubManager(requester, "MEMBER");

        InviteVendorMemberRequest req = new InviteVendorMemberRequest();
        req.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.invite(tenantId, requester, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void inviteRejectsAlreadyActiveMember() {
        stubManager(ownerId, "OWNER");
        UUID target = UUID.randomUUID();
        VendorMember existing = memberWithRole(target, "MEMBER");
        existing.setId(UUID.randomUUID());
        when(memberRepo.findByTenantIdAndUserId(tenantId, target)).thenReturn(Optional.of(existing));

        InviteVendorMemberRequest req = new InviteVendorMemberRequest();
        req.setUserId(target);

        assertThatThrownBy(() -> service.invite(tenantId, ownerId, req))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(any());
    }

    @Test
    void inviteSucceedsFromOwner() {
        stubManager(ownerId, "OWNER");
        UUID target = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, target)).thenReturn(Optional.empty());

        InviteVendorMemberRequest req = new InviteVendorMemberRequest();
        req.setUserId(target);
        req.setRole("MEMBER");

        service.invite(tenantId, ownerId, req);

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(target) && "MEMBER".equals(m.getRole())
                && "ACTIVE".equals(m.getStatus())));
    }

    @Test
    void inviteReactivatesRemovedMember() {
        stubManager(ownerId, "OWNER");
        UUID target = UUID.randomUUID();
        VendorMember removed = memberWithRole(target, "MEMBER");
        removed.setId(UUID.randomUUID());
        removed.setStatus("REMOVED");
        removed.setRemovedBy(UUID.randomUUID());
        removed.setRemovedAt(Instant.now());
        when(memberRepo.findByTenantIdAndUserId(tenantId, target)).thenReturn(Optional.of(removed));

        InviteVendorMemberRequest req = new InviteVendorMemberRequest();
        req.setUserId(target);
        req.setRole("MEMBER");

        service.invite(tenantId, ownerId, req);

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(target)
                && "ACTIVE".equals(m.getStatus()) && m.getRemovedBy() == null && m.getRemovedAt() == null));
    }

    // ── updateRole() ─────────────────────────────────────────────────────────

    @Test
    void updateRoleSucceedsFromAdmin() {
        UUID admin = UUID.randomUUID();
        stubManager(admin, "ADMIN");
        UUID target = UUID.randomUUID();
        VendorMember targetMember = memberWithRole(target, "MEMBER");
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, target, "ACTIVE"))
                .thenReturn(Optional.of(targetMember));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        UpdateVendorMemberRoleRequest req = new UpdateVendorMemberRoleRequest();
        req.setRole("ADMIN");

        var response = service.updateRole(tenantId, admin, target, req);

        assertThat(response.getRole()).isEqualTo("ADMIN");
    }

    @Test
    void updateRoleRejectsSelfAction() {
        stubManager(ownerId, "OWNER");

        UpdateVendorMemberRoleRequest req = new UpdateVendorMemberRoleRequest();
        req.setRole("MEMBER");

        assertThatThrownBy(() -> service.updateRole(tenantId, ownerId, ownerId, req))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(any());
    }

    @Test
    void updateRoleRejectsDemotingLastManager() {
        // requester != target, so the self-action guard doesn't fire — this isolates the
        // last-manager guard specifically. The mocked findByTenantId list deliberately omits the
        // requester's own row: in real DB reads it would always be present (requireManager
        // already proved the requester is themselves an active manager), so this scenario can't
        // arise via this call path today — it pins the guard as defense-in-depth against a
        // future admin-bypass path that authorizes without itself holding membership.
        UUID requester = UUID.randomUUID();
        stubManager(requester, "ADMIN");
        UUID target = UUID.randomUUID();
        VendorMember soleManager = memberWithRole(target, "ADMIN");
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, target, "ACTIVE"))
                .thenReturn(Optional.of(soleManager));
        when(memberRepo.findByTenantId(tenantId)).thenReturn(List.of(soleManager,
                memberWithRole(UUID.randomUUID(), "MEMBER")));

        UpdateVendorMemberRoleRequest req = new UpdateVendorMemberRoleRequest();
        req.setRole("MEMBER");

        assertThatThrownBy(() -> service.updateRole(tenantId, requester, target, req))
                .isInstanceOf(ValidationException.class);
    }

    // ── remove() ─────────────────────────────────────────────────────────────

    @Test
    void removeRejectsRemovingTheRegisteringOwner() {
        UUID admin = UUID.randomUUID();
        stubManager(admin, "ADMIN");

        assertThatThrownBy(() -> service.remove(tenantId, admin, ownerId))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(any());
        verify(memberRepo, never()).delete(any());
    }

    @Test
    void removeRejectsSelfAction() {
        UUID admin = UUID.randomUUID();
        stubManager(admin, "ADMIN");

        assertThatThrownBy(() -> service.remove(tenantId, admin, admin))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(any());
    }

    @Test
    void removeSucceedsForNonOwnerTarget() {
        stubManager(ownerId, "OWNER");
        UUID target = UUID.randomUUID();
        VendorMember targetMember = memberWithRole(target, "MEMBER");
        when(memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, target, "ACTIVE"))
                .thenReturn(Optional.of(targetMember));

        service.remove(tenantId, ownerId, target);

        verify(memberRepo, never()).delete(any());
        verify(memberRepo).save(argThat(m -> "REMOVED".equals(m.getStatus()) && m.getRemovedBy().equals(ownerId)));
    }
}
