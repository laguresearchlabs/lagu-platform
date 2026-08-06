package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventJoinRequest;
import com.lagu.platform.event.domain.EventJoinRequestRepository;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.dto.CreateJoinRequestRequest;
import com.lagu.platform.event.dto.InviteMemberRequest;
import com.lagu.platform.event.dto.UpdateMemberRoleRequest;
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

class EventMemberServiceTest {

    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EventMemberRepository memberRepo = mock(EventMemberRepository.class);
    private final EventJoinRequestRepository joinRequestRepo = mock(EventJoinRequestRepository.class);
    private final EventMemberService service = new EventMemberService(eventRepo, memberRepo, joinRequestRepo);

    private final UUID eventId = UUID.randomUUID();
    // Event.id doubles as the org-partition key now (see Event.java) — kept as a separate local
    // so the rest of this file's existing "tenantId" naming for EventMember/EventJoinRequest lookups
    // didn't need a sweeping rename, it's just an alias for eventId.
    private final UUID tenantId = eventId;
    private final UUID ownerId = UUID.randomUUID();

    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setId(eventId);
        event.setOwnerUserId(ownerId);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(event));
    }

    private EventMember memberWithRole(UUID userId, String role) {
        EventMember m = new EventMember();
        m.setTenantId(tenantId);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus("ACCEPTED");
        return m;
    }

    private void stubManager(UUID userId, String role) {
        when(memberRepo.findByTenantIdAndUserIdAndStatusNot(tenantId, userId, "REMOVED"))
                .thenReturn(Optional.of(memberWithRole(userId, role)));
    }

    // ── invite() ─────────────────────────────────────────────────────────────

    @Test
    void inviteRejectedFromNonManager() {
        UUID requester = UUID.randomUUID();
        stubManager(requester, "INVITEE");

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.invite(eventId, requester, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void inviteRejectsAlreadyExistingMember() {
        stubManager(ownerId, "ADMIN");
        UUID targetUser = UUID.randomUUID();
        EventMember existing = memberWithRole(targetUser, "INVITEE");
        existing.setId(UUID.randomUUID());
        existing.setStatus("ACCEPTED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, targetUser)).thenReturn(Optional.of(existing));

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(targetUser);

        assertThatThrownBy(() -> service.invite(eventId, ownerId, req))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(any());
    }

    @Test
    void inviteRejectsInvalidRole() {
        stubManager(ownerId, "ADMIN");

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(UUID.randomUUID());
        req.setRole("SUPERUSER");

        assertThatThrownBy(() -> service.invite(eventId, ownerId, req))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void inviteSucceedsFromAdmin() {
        stubManager(ownerId, "ADMIN");
        UUID targetUser = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, targetUser)).thenReturn(Optional.empty());

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(targetUser);
        req.setRole("MAINTAINER");

        service.invite(eventId, ownerId, req);

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(targetUser)
                && "MAINTAINER".equals(m.getRole()) && "INVITED".equals(m.getStatus())));
    }

    @Test
    void inviteReactivatesDeclinedMember() {
        stubManager(ownerId, "ADMIN");
        UUID targetUser = UUID.randomUUID();
        EventMember declined = memberWithRole(targetUser, "INVITEE");
        declined.setId(UUID.randomUUID());
        declined.setStatus("DECLINED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, targetUser)).thenReturn(Optional.of(declined));

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(targetUser);
        req.setRole("INVITEE");

        service.invite(eventId, ownerId, req);

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(targetUser) && "INVITED".equals(m.getStatus())));
    }

    @Test
    void inviteReactivatesRemovedMember() {
        stubManager(ownerId, "ADMIN");
        UUID targetUser = UUID.randomUUID();
        EventMember removed = memberWithRole(targetUser, "INVITEE");
        removed.setId(UUID.randomUUID());
        removed.setStatus("REMOVED");
        removed.setRemovedBy(UUID.randomUUID());
        removed.setRemovedAt(Instant.now());
        when(memberRepo.findByTenantIdAndUserId(tenantId, targetUser)).thenReturn(Optional.of(removed));

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(targetUser);
        req.setRole("INVITEE");

        service.invite(eventId, ownerId, req);

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(targetUser)
                && "INVITED".equals(m.getStatus()) && m.getRemovedBy() == null && m.getRemovedAt() == null));
    }

    // ── updateRole() ─────────────────────────────────────────────────────────

    @Test
    void updateRoleRejectsSelfAction() {
        stubManager(ownerId, "ADMIN");

        UpdateMemberRoleRequest req = new UpdateMemberRoleRequest();
        req.setRole("MAINTAINER");

        assertThatThrownBy(() -> service.updateRole(eventId, ownerId, ownerId, req))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(any());
    }

    @Test
    void updateRoleRejectsDemotingLastAdminEvenWithMaintainerPresent() {
        // Proves the last-manager guard uses the narrower {ADMIN} set, not canManage()'s
        // {ADMIN, MAINTAINER} authorization gate — a remaining MAINTAINER must NOT count as
        // "another manager" for this guard, or the sole ADMIN could be demoted with no one
        // left able to approve join requests / manage roles.
        UUID requester = UUID.randomUUID();
        stubManager(requester, "ADMIN");
        UUID target = UUID.randomUUID();
        EventMember soleAdmin = memberWithRole(target, "ADMIN");
        when(memberRepo.findByTenantIdAndUserIdAndStatusNot(tenantId, target, "REMOVED"))
                .thenReturn(Optional.of(soleAdmin));
        EventMember maintainer = memberWithRole(UUID.randomUUID(), "MAINTAINER");
        when(memberRepo.findByTenantId(tenantId)).thenReturn(List.of(soleAdmin, maintainer));

        UpdateMemberRoleRequest req = new UpdateMemberRoleRequest();
        req.setRole("MAINTAINER");

        assertThatThrownBy(() -> service.updateRole(eventId, requester, target, req))
                .isInstanceOf(ValidationException.class);
    }

    // ── remove() ─────────────────────────────────────────────────────────────

    @Test
    void removeRejectsRemovingTheEventOwner() {
        UUID admin = UUID.randomUUID();
        stubManager(admin, "ADMIN");

        assertThatThrownBy(() -> service.remove(eventId, admin, ownerId))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).delete(any());
        verify(memberRepo, never()).save(any());
    }

    @Test
    void removeRejectsSelfAction() {
        UUID admin = UUID.randomUUID();
        stubManager(admin, "ADMIN");

        assertThatThrownBy(() -> service.remove(eventId, admin, admin))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(any());
    }

    @Test
    void removeSucceedsForNonOwnerTarget() {
        stubManager(ownerId, "ADMIN");
        UUID targetUser = UUID.randomUUID();
        EventMember target = memberWithRole(targetUser, "INVITEE");
        when(memberRepo.findByTenantIdAndUserIdAndStatusNot(tenantId, targetUser, "REMOVED"))
                .thenReturn(Optional.of(target));

        service.remove(eventId, ownerId, targetUser);

        verify(memberRepo, never()).delete(any());
        verify(memberRepo).save(argThat(m -> "REMOVED".equals(m.getStatus()) && m.getRemovedBy().equals(ownerId)));
    }

    // ── respondToInvite() ───────────────────────────────────────────────────────

    @Test
    void respondToInviteRejectsWhenNoPendingInvite() {
        UUID invitee = UUID.randomUUID();
        EventMember accepted = memberWithRole(invitee, "INVITEE");
        accepted.setStatus("ACCEPTED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, invitee)).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.respondToInvite(eventId, invitee, true))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void respondToInviteAcceptSetsAccepted() {
        UUID invitee = UUID.randomUUID();
        EventMember invited = memberWithRole(invitee, "INVITEE");
        invited.setStatus("INVITED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, invitee)).thenReturn(Optional.of(invited));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.respondToInvite(eventId, invitee, true);
        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void respondToInviteDeclineSetsDeclined() {
        UUID invitee = UUID.randomUUID();
        EventMember invited = memberWithRole(invitee, "INVITEE");
        invited.setStatus("INVITED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, invitee)).thenReturn(Optional.of(invited));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.respondToInvite(eventId, invitee, false);
        assertThat(response.getStatus()).isEqualTo("DECLINED");
    }

    // ── join requests ────────────────────────────────────────────────────────

    @Test
    void requestToJoinRejectedIfAlreadyMember() {
        UUID requester = UUID.randomUUID();
        EventMember existing = memberWithRole(requester, "INVITEE");
        existing.setStatus("ACCEPTED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, requester)).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> service.requestToJoin(eventId, requester, new CreateJoinRequestRequest()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requestToJoinAllowedAfterPriorDecline() {
        UUID requester = UUID.randomUUID();
        EventMember declined = memberWithRole(requester, "INVITEE");
        declined.setStatus("DECLINED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, requester)).thenReturn(Optional.of(declined));
        when(joinRequestRepo.findByTenantIdAndUserId(tenantId, requester)).thenReturn(Optional.empty());

        service.requestToJoin(eventId, requester, new CreateJoinRequestRequest());

        verify(joinRequestRepo).save(any());
    }

    @Test
    void requestToJoinRejectedIfAlreadyPending() {
        UUID requester = UUID.randomUUID();
        when(memberRepo.findByTenantIdAndUserId(tenantId, requester)).thenReturn(Optional.empty());
        when(joinRequestRepo.findByTenantIdAndUserId(tenantId, requester))
                .thenReturn(Optional.of(new EventJoinRequest()));

        assertThatThrownBy(() -> service.requestToJoin(eventId, requester, new CreateJoinRequestRequest()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requestToJoinRevivesSettledRequest() {
        UUID requester = UUID.randomUUID();
        UUID reviewer = UUID.randomUUID();
        EventJoinRequest rejected = new EventJoinRequest();
        rejected.setId(UUID.randomUUID());
        rejected.setTenantId(tenantId);
        rejected.setUserId(requester);
        rejected.setStatus("REJECTED");
        rejected.setReviewedByUserId(reviewer);
        rejected.setReviewedAt(Instant.now());
        when(memberRepo.findByTenantIdAndUserId(tenantId, requester)).thenReturn(Optional.empty());
        when(joinRequestRepo.findByTenantIdAndUserId(tenantId, requester)).thenReturn(Optional.of(rejected));

        var response = service.requestToJoin(eventId, requester, new CreateJoinRequestRequest());

        // The UNIQUE (tenant_id, user_id) row is reused, not duplicated, and the previous
        // review is cleared so the organizer sees a genuinely pending request again.
        assertThat(response.getStatus()).isEqualTo("PENDING");
        assertThat(response.getId()).isEqualTo(rejected.getId());
        assertThat(rejected.getReviewedByUserId()).isNull();
        assertThat(rejected.getReviewedAt()).isNull();
        verify(joinRequestRepo).save(rejected);
    }

    @Test
    void approveRejectsJoinRequestFromAnotherEvent() {
        stubManager(ownerId, "ADMIN");

        EventJoinRequest foreign = new EventJoinRequest();
        foreign.setId(UUID.randomUUID());
        foreign.setTenantId(UUID.randomUUID()); // different event's org
        when(joinRequestRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.approve(eventId, ownerId, foreign.getId()))
                .isInstanceOf(com.lagu.platform.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void approveCreatesAcceptedMemberWithRequestedRole() {
        stubManager(ownerId, "ADMIN");

        UUID requesterUserId = UUID.randomUUID();
        EventJoinRequest jr = new EventJoinRequest();
        jr.setId(UUID.randomUUID());
        jr.setTenantId(tenantId);
        jr.setUserId(requesterUserId);
        jr.setRequestedRole("MAINTAINER");
        when(joinRequestRepo.findById(jr.getId())).thenReturn(Optional.of(jr));
        when(memberRepo.findByTenantIdAndUserId(tenantId, requesterUserId)).thenReturn(Optional.empty());
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(eventId, ownerId, jr.getId());

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(requesterUserId)
                && "MAINTAINER".equals(m.getRole()) && "ACCEPTED".equals(m.getStatus())));
        verify(joinRequestRepo).save(argThat(r -> "APPROVED".equals(r.getStatus())));
    }

    @Test
    void approveRejectsWhenTargetAlreadyActiveMember() {
        stubManager(ownerId, "ADMIN");

        UUID requesterUserId = UUID.randomUUID();
        EventJoinRequest jr = new EventJoinRequest();
        jr.setId(UUID.randomUUID());
        jr.setTenantId(tenantId);
        jr.setUserId(requesterUserId);
        jr.setRequestedRole("INVITEE");
        when(joinRequestRepo.findById(jr.getId())).thenReturn(Optional.of(jr));

        EventMember alreadyActive = memberWithRole(requesterUserId, "MAINTAINER");
        alreadyActive.setStatus("ACCEPTED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, requesterUserId)).thenReturn(Optional.of(alreadyActive));

        assertThatThrownBy(() -> service.approve(eventId, ownerId, jr.getId()))
                .isInstanceOf(ValidationException.class);
        verify(joinRequestRepo).save(argThat(r -> "REJECTED".equals(r.getStatus())));
        verify(memberRepo, never()).save(any());
    }

    @Test
    void approveReactivatesRemovedMember() {
        stubManager(ownerId, "ADMIN");

        UUID requesterUserId = UUID.randomUUID();
        EventJoinRequest jr = new EventJoinRequest();
        jr.setId(UUID.randomUUID());
        jr.setTenantId(tenantId);
        jr.setUserId(requesterUserId);
        jr.setRequestedRole("MAINTAINER");
        when(joinRequestRepo.findById(jr.getId())).thenReturn(Optional.of(jr));

        EventMember removed = memberWithRole(requesterUserId, "INVITEE");
        removed.setId(UUID.randomUUID());
        removed.setStatus("REMOVED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, requesterUserId)).thenReturn(Optional.of(removed));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(eventId, ownerId, jr.getId());

        verify(memberRepo).save(argThat(m -> m.getId().equals(removed.getId())
                && "ACCEPTED".equals(m.getStatus()) && "MAINTAINER".equals(m.getRole())));
        verify(joinRequestRepo).save(argThat(r -> "APPROVED".equals(r.getStatus())));
    }
}
