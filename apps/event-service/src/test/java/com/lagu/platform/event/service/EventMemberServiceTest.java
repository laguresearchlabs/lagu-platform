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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

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
    private final UUID orgId = UUID.randomUUID();
    private final UUID ownerId = UUID.randomUUID();

    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setId(eventId);
        event.setOrgId(orgId);
        event.setOwnerUserId(ownerId);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(event));
    }

    private EventMember memberWithRole(UUID userId, String role) {
        EventMember m = new EventMember();
        m.setOrgId(orgId);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus("ACCEPTED");
        return m;
    }

    // ── invite() ─────────────────────────────────────────────────────────────

    @Test
    void inviteRejectedFromNonManager() {
        UUID requester = UUID.randomUUID();
        when(memberRepo.findByOrgIdAndUserId(orgId, requester))
                .thenReturn(Optional.of(memberWithRole(requester, "INVITEE")));

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(UUID.randomUUID());

        assertThatThrownBy(() -> service.invite(eventId, requester, req))
                .isInstanceOf(ResponseStatusException.class)
                .satisfies(e -> assertThat(((ResponseStatusException) e).getStatusCode())
                        .isEqualTo(HttpStatus.FORBIDDEN));
    }

    @Test
    void inviteRejectsAlreadyExistingMember() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN")));
        UUID targetUser = UUID.randomUUID();
        when(memberRepo.existsByOrgIdAndUserId(orgId, targetUser)).thenReturn(true);

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(targetUser);

        assertThatThrownBy(() -> service.invite(eventId, ownerId, req))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).save(argThat(m -> m.getUserId().equals(targetUser)));
    }

    @Test
    void inviteRejectsInvalidRole() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN")));

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(UUID.randomUUID());
        req.setRole("SUPERUSER");

        assertThatThrownBy(() -> service.invite(eventId, ownerId, req))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void inviteSucceedsFromAdmin() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN")));
        UUID targetUser = UUID.randomUUID();
        when(memberRepo.existsByOrgIdAndUserId(orgId, targetUser)).thenReturn(false);

        InviteMemberRequest req = new InviteMemberRequest();
        req.setUserId(targetUser);
        req.setRole("MAINTAINER");

        service.invite(eventId, ownerId, req);

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(targetUser)
                && "MAINTAINER".equals(m.getRole()) && "INVITED".equals(m.getStatus())));
    }

    // ── remove() ─────────────────────────────────────────────────────────────

    @Test
    void removeRejectsRemovingTheEventOwner() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN")));

        assertThatThrownBy(() -> service.remove(eventId, ownerId, ownerId))
                .isInstanceOf(ValidationException.class);
        verify(memberRepo, never()).delete(any());
    }

    @Test
    void removeSucceedsForNonOwnerTarget() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN")));
        UUID targetUser = UUID.randomUUID();
        EventMember target = memberWithRole(targetUser, "INVITEE");
        when(memberRepo.findByOrgIdAndUserId(orgId, targetUser)).thenReturn(Optional.of(target));

        service.remove(eventId, ownerId, targetUser);

        verify(memberRepo).delete(target);
    }

    // ── respondToInvite() ───────────────────────────────────────────────────────

    @Test
    void respondToInviteRejectsWhenNoPendingInvite() {
        UUID invitee = UUID.randomUUID();
        EventMember accepted = memberWithRole(invitee, "INVITEE");
        accepted.setStatus("ACCEPTED");
        when(memberRepo.findByOrgIdAndUserId(orgId, invitee)).thenReturn(Optional.of(accepted));

        assertThatThrownBy(() -> service.respondToInvite(eventId, invitee, true))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void respondToInviteAcceptSetsAccepted() {
        UUID invitee = UUID.randomUUID();
        EventMember invited = memberWithRole(invitee, "INVITEE");
        invited.setStatus("INVITED");
        when(memberRepo.findByOrgIdAndUserId(orgId, invitee)).thenReturn(Optional.of(invited));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.respondToInvite(eventId, invitee, true);
        assertThat(response.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void respondToInviteDeclineSetsDeclined() {
        UUID invitee = UUID.randomUUID();
        EventMember invited = memberWithRole(invitee, "INVITEE");
        invited.setStatus("INVITED");
        when(memberRepo.findByOrgIdAndUserId(orgId, invitee)).thenReturn(Optional.of(invited));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        var response = service.respondToInvite(eventId, invitee, false);
        assertThat(response.getStatus()).isEqualTo("DECLINED");
    }

    // ── join requests ────────────────────────────────────────────────────────

    @Test
    void requestToJoinRejectedIfAlreadyMember() {
        UUID requester = UUID.randomUUID();
        when(memberRepo.existsByOrgIdAndUserId(orgId, requester)).thenReturn(true);

        assertThatThrownBy(() -> service.requestToJoin(eventId, requester, new CreateJoinRequestRequest()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void requestToJoinRejectedIfAlreadyPending() {
        UUID requester = UUID.randomUUID();
        when(memberRepo.existsByOrgIdAndUserId(orgId, requester)).thenReturn(false);
        when(joinRequestRepo.findByOrgIdAndUserId(orgId, requester))
                .thenReturn(Optional.of(new EventJoinRequest()));

        assertThatThrownBy(() -> service.requestToJoin(eventId, requester, new CreateJoinRequestRequest()))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void approveRejectsJoinRequestFromAnotherEvent() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN")));

        EventJoinRequest foreign = new EventJoinRequest();
        foreign.setId(UUID.randomUUID());
        foreign.setOrgId(UUID.randomUUID()); // different event's org
        when(joinRequestRepo.findById(foreign.getId())).thenReturn(Optional.of(foreign));

        assertThatThrownBy(() -> service.approve(eventId, ownerId, foreign.getId()))
                .isInstanceOf(com.lagu.platform.common.exception.ResourceNotFoundException.class);
    }

    @Test
    void approveCreatesAcceptedMemberWithRequestedRole() {
        when(memberRepo.findByOrgIdAndUserId(orgId, ownerId))
                .thenReturn(Optional.of(memberWithRole(ownerId, "ADMIN")));

        UUID requesterUserId = UUID.randomUUID();
        EventJoinRequest jr = new EventJoinRequest();
        jr.setId(UUID.randomUUID());
        jr.setOrgId(orgId);
        jr.setUserId(requesterUserId);
        jr.setRequestedRole("MAINTAINER");
        when(joinRequestRepo.findById(jr.getId())).thenReturn(Optional.of(jr));
        when(memberRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));

        service.approve(eventId, ownerId, jr.getId());

        verify(memberRepo).save(argThat(m -> m.getUserId().equals(requesterUserId)
                && "MAINTAINER".equals(m.getRole()) && "ACCEPTED".equals(m.getStatus())));
        verify(joinRequestRepo).save(argThat(r -> "APPROVED".equals(r.getStatus())));
    }
}
