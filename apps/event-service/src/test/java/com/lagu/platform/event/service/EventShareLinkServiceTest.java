package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventJoinRequest;
import com.lagu.platform.event.domain.EventJoinRequestRepository;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.domain.EventShareLink;
import com.lagu.platform.event.domain.EventShareLinkRepository;
import com.lagu.platform.event.dto.ClaimShareLinkResponse;
import com.lagu.platform.event.dto.CreateShareLinkRequest;
import com.lagu.platform.event.dto.ShareLinkResponse;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Share links as capabilities.
 *
 * <p>What matters here is what a token is worth. Before this existed a "share link" was the event
 * id and granted nothing — access came from the record's {@code visibility} field — so there was
 * no link to revoke and revoking one would have changed nothing. These tests pin the properties
 * that make revocation mean something: the raw token exists exactly once, every way a link can be
 * dead looks identical from outside, and a link that does not auto-admit cannot be waved through
 * by its own holder.
 */
class EventShareLinkServiceTest {

    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EventMemberRepository memberRepo = mock(EventMemberRepository.class);
    private final EventShareLinkRepository linkRepo = mock(EventShareLinkRepository.class);
    private final EventJoinRequestRepository joinRequestRepo = mock(EventJoinRequestRepository.class);

    private final EventShareLinkService service =
            new EventShareLinkService(eventRepo, memberRepo, linkRepo, joinRequestRepo);

    private final UUID eventId = UUID.randomUUID();
    private final UUID hostId = UUID.randomUUID();
    private final UUID guestId = UUID.randomUUID();

    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setId(eventId);
        event.setOwnerUserId(hostId);
        when(eventRepo.findById(eventId)).thenReturn(Optional.of(event));
        when(linkRepo.save(any(EventShareLink.class))).thenAnswer(inv -> inv.getArgument(0));
        when(memberRepo.save(any(EventMember.class))).thenAnswer(inv -> inv.getArgument(0));
        when(joinRequestRepo.save(any(EventJoinRequest.class))).thenAnswer(inv -> inv.getArgument(0));
        stubMember(hostId, "ADMIN", "ACCEPTED");
    }

    private void stubMember(UUID userId, String role, String status) {
        EventMember m = new EventMember();
        m.setTenantId(eventId);
        m.setUserId(userId);
        m.setRole(role);
        m.setStatus(status);
        when(memberRepo.findByTenantIdAndUserIdAndStatusNot(eventId, userId, "REMOVED"))
                .thenReturn(Optional.of(m));
        when(memberRepo.findByTenantIdAndUserId(eventId, userId)).thenReturn(Optional.of(m));
    }

    private EventShareLink liveLink(boolean autoAdmit) {
        EventShareLink link = new EventShareLink();
        link.setId(UUID.randomUUID());
        link.setEventId(eventId);
        link.setAutoAdmit(autoAdmit);
        link.setCreatedBy(hostId);
        return link;
    }

    /** Registers a link under a known raw token, the way the anonymous path will look it up. */
    private String publish(EventShareLink link) {
        String token = EventShareLinkService.newToken();
        link.setTokenHash(EventShareLinkService.hash(token));
        when(linkRepo.findByTokenHash(link.getTokenHash())).thenReturn(Optional.of(link));
        return token;
    }

    // ── minting ──────────────────────────────────────────────────────────────

    @Test
    void returnsTheRawTokenExactlyOnce() {
        ShareLinkResponse created = service.create(eventId, hostId, new CreateShareLinkRequest());
        assertThat(created.getToken()).isNotBlank();

        // Stored hashed, so the token cannot be recovered afterwards — a dump of the table must
        // not yield working links.
        EventShareLink saved = captureSaved();
        assertThat(saved.getTokenHash()).isNotEqualTo(created.getToken());
        assertThat(saved.getTokenHash()).hasSize(64);

        when(linkRepo.findByEventIdOrderByCreatedAtDesc(eventId)).thenReturn(List.of(saved));
        assertThat(service.list(eventId, hostId)).singleElement()
                .extracting(ShareLinkResponse::getToken).isNull();
    }

    @Test
    void mintsAnUnguessableTokenThatIsNotAUuid() {
        String a = EventShareLinkService.newToken();
        String b = EventShareLinkService.newToken();
        assertThat(a).isNotEqualTo(b);
        // 160 bits base64url. A UUID would be 122 bits and, worse, would look like an id — the
        // very thing this change stops the token from being. Assert the UUID *shape*, not the
        // absence of a hyphen: '-' is in the base64url alphabet, so a plain doesNotContain("-")
        // failed on roughly a third of runs.
        assertThat(a).hasSize(27);
        assertThat(a).doesNotMatch("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}");
    }

    @Test
    void onlyEverGrantsInvitee() {
        ShareLinkResponse created = service.create(eventId, hostId, new CreateShareLinkRequest());
        assertThat(created.getGrantsRole()).isEqualTo("INVITEE");
    }

    @Test
    void refusesAnExpiryInThePast() {
        CreateShareLinkRequest req = new CreateShareLinkRequest();
        req.setExpiresAt(Instant.now().minus(1, ChronoUnit.HOURS));
        assertThatThrownBy(() -> service.create(eventId, hostId, req))
                .isInstanceOf(ValidationException.class);
    }

    /**
     * A guest may bring a friend — the event URL they could paste instead 403s for anyone not
     * already invited, so without this they had no way at all.
     *
     * <p>What they cannot mint is a door that opens itself. `autoAdmit` is forced false whatever
     * they ask for, so the link files a join request and the host approves it.
     */
    @Test
    void aGuestMintsAnApprovalOnlyLinkWhateverTheyAskFor() {
        stubMember(guestId, "INVITEE", "ACCEPTED");
        CreateShareLinkRequest req = new CreateShareLinkRequest();
        req.setAutoAdmit(true);

        service.create(eventId, guestId, req);

        assertThat(captureSaved().isAutoAdmit()).isFalse();
    }

    @Test
    void aManagerKeepsTheChoiceOfAutoAdmit() {
        CreateShareLinkRequest req = new CreateShareLinkRequest();
        req.setAutoAdmit(true);

        service.create(eventId, hostId, req);

        assertThat(captureSaved().isAutoAdmit()).isTrue();
    }

    // ── resolution: every dead link looks the same ───────────────────────────

    @Test
    void resolvesALiveToken() {
        EventShareLink link = liveLink(true);
        String token = publish(link);
        assertThat(service.resolve(token).getId()).isEqualTo(link.getId());
    }

    @Test
    void revokedExpiredExhaustedAndUnknownAreIndistinguishable() {
        // Anything other than one identical 404 would confirm the event exists to the person the
        // link was just taken away from.
        EventShareLink revoked = liveLink(true);
        revoked.setRevokedAt(Instant.now());
        String revokedToken = publish(revoked);

        EventShareLink expired = liveLink(true);
        expired.setExpiresAt(Instant.now().minus(1, ChronoUnit.MINUTES));
        String expiredToken = publish(expired);

        EventShareLink exhausted = liveLink(true);
        exhausted.setMaxUses(1);
        exhausted.setUseCount(1);
        String exhaustedToken = publish(exhausted);

        when(linkRepo.findByTokenHash(anyString())).thenReturn(Optional.empty());
        when(linkRepo.findByTokenHash(EventShareLinkService.hash(revokedToken))).thenReturn(Optional.of(revoked));
        when(linkRepo.findByTokenHash(EventShareLinkService.hash(expiredToken))).thenReturn(Optional.of(expired));
        when(linkRepo.findByTokenHash(EventShareLinkService.hash(exhaustedToken))).thenReturn(Optional.of(exhausted));

        for (String token : List.of(revokedToken, expiredToken, exhaustedToken, "never-existed")) {
            assertThatThrownBy(() -> service.resolve(token))
                    .isInstanceOf(ResourceNotFoundException.class);
        }
    }

    @Test
    void anExpiryExactlyNowIsAlreadyDead() {
        EventShareLink link = liveLink(true);
        link.setExpiresAt(Instant.now().minusMillis(1));
        assertThat(link.isLive(Instant.now())).isFalse();
    }

    // ── claiming ─────────────────────────────────────────────────────────────

    @Test
    void anAutoAdmitLinkJoinsTheHolderOutright() {
        String token = publish(liveLink(true));
        when(memberRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.empty());

        ClaimShareLinkResponse claim = service.claim(token, guestId);

        assertThat(claim.getOutcome()).isEqualTo("JOINED");
        EventMember member = captureMember();
        assertThat(member.getStatus()).isEqualTo("ACCEPTED");
        assertThat(member.getRole()).isEqualTo("INVITEE");
        // Stamped, so "revoke this link and remove everyone it let in" is answerable.
        assertThat(member.getJoinedViaShareLinkId()).isNotNull();
    }

    @Test
    void aNonAutoAdmitLinkRaisesAJoinRequestAndNeverAnInvitedMember() {
        // This is the whole point of the branch. respondToInvite() lets an INVITED member accept
        // themselves — that is what the endpoint is for — so parking the holder there would let
        // them wave themselves through and make autoAdmit=false mean nothing.
        String token = publish(liveLink(false));
        when(memberRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.empty());
        when(joinRequestRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.empty());

        ClaimShareLinkResponse claim = service.claim(token, guestId);

        assertThat(claim.getOutcome()).isEqualTo("REQUESTED");
        verify(memberRepo, never()).save(any(EventMember.class));
        assertThat(captureJoinRequest().getStatus()).isEqualTo("PENDING");
    }

    @Test
    void claimingTwiceIsNotAnError() {
        // Opening the same WhatsApp link twice is ordinary; the second tap must not fail.
        String token = publish(liveLink(true));
        EventMember existing = new EventMember();
        existing.setTenantId(eventId);
        existing.setUserId(guestId);
        existing.setRole("INVITEE");
        existing.setStatus("ACCEPTED");
        when(memberRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.of(existing));

        assertThat(service.claim(token, guestId).getOutcome()).isEqualTo("ALREADY_MEMBER");
        verify(linkRepo, never()).save(any(EventShareLink.class));
    }

    @Test
    void aPendingRequestIsReportedRatherThanDuplicated() {
        String token = publish(liveLink(false));
        when(memberRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.empty());
        EventJoinRequest pending = new EventJoinRequest();
        pending.setStatus("PENDING");
        when(joinRequestRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.of(pending));

        assertThat(service.claim(token, guestId).getOutcome()).isEqualTo("REQUESTED");
        verify(joinRequestRepo, never()).save(any(EventJoinRequest.class));
    }

    @Test
    void aRemovedMemberIsNotSilentlyReadmitted() {
        // REMOVED was an organiser's decision. Reviving the row is fine — it is UNIQUE per user —
        // but it must go back through the link's own rules, not slip past as "already a member".
        String token = publish(liveLink(false));
        EventMember removed = new EventMember();
        removed.setTenantId(eventId);
        removed.setUserId(guestId);
        removed.setStatus("REMOVED");
        when(memberRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.of(removed));
        when(joinRequestRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.empty());

        assertThat(service.claim(token, guestId).getOutcome()).isEqualTo("REQUESTED");
    }

    @Test
    void countsUsesOnRedemptionOnly() {
        EventShareLink link = liveLink(true);
        String token = publish(link);
        when(memberRepo.findByTenantIdAndUserId(eventId, guestId)).thenReturn(Optional.empty());

        // Resolving is the preview path — every crawler refresh hits it, so it must not write.
        service.resolve(token);
        verify(linkRepo, never()).save(any(EventShareLink.class));

        service.claim(token, guestId);
        assertThat(link.getUseCount()).isEqualTo(1);
        assertThat(link.getLastUsedAt()).isNotNull();
    }

    // ── revocation ───────────────────────────────────────────────────────────

    @Test
    void revokingMarksTheRowRatherThanDeletingIt() {
        EventShareLink link = liveLink(true);
        when(linkRepo.findByIdAndEventId(link.getId(), eventId)).thenReturn(Optional.of(link));

        service.revoke(eventId, hostId, link.getId(), false);

        assertThat(link.getRevokedAt()).isNotNull();
        assertThat(link.getRevokedBy()).isEqualTo(hostId);
        assertThat(link.isLive(Instant.now())).isFalse();
        verify(linkRepo, never()).delete(any());
    }

    @Test
    void revokingLeavesTheMembersItAdmittedInPlace() {
        // Closing the door is not emptying the room: someone who joined three weeks ago is a
        // member now, and the link is how they arrived, not what keeps them here.
        EventShareLink link = liveLink(true);
        when(linkRepo.findByIdAndEventId(link.getId(), eventId)).thenReturn(Optional.of(link));

        service.revoke(eventId, hostId, link.getId(), false);

        verify(memberRepo, never()).saveAll(any());
    }

    @Test
    void removeJoinedSweepsOnlyThatLinksMembersAndNeverTheOwner() {
        EventShareLink link = liveLink(true);
        when(linkRepo.findByIdAndEventId(link.getId(), eventId)).thenReturn(Optional.of(link));

        EventMember guest = new EventMember();
        guest.setUserId(guestId);
        guest.setStatus("ACCEPTED");
        EventMember owner = new EventMember();
        owner.setUserId(hostId);
        owner.setStatus("ACCEPTED");
        when(memberRepo.findByJoinedViaShareLinkId(link.getId())).thenReturn(List.of(guest, owner));

        service.revoke(eventId, hostId, link.getId(), true);

        assertThat(guest.getStatus()).isEqualTo("REMOVED");
        // Whatever the owner arrived through, this must never lock them out of their own event.
        assertThat(owner.getStatus()).isEqualTo("ACCEPTED");
    }

    @Test
    void refusesToRevokeALinkBelongingToAnotherEvent() {
        UUID foreignLink = UUID.randomUUID();
        when(linkRepo.findByIdAndEventId(foreignLink, eventId)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.revoke(eventId, hostId, foreignLink, false))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /** Your own door to close — but not anyone else's, and not the host's. */
    @Test
    void aGuestMayRevokeOnlyTheLinkTheyMinted() {
        stubMember(guestId, "INVITEE", "ACCEPTED");

        EventShareLink theirs = liveLink(false);
        theirs.setCreatedBy(guestId);
        when(linkRepo.findByIdAndEventId(theirs.getId(), eventId)).thenReturn(Optional.of(theirs));
        service.revoke(eventId, guestId, theirs.getId(), false);
        assertThat(theirs.isRevoked()).isTrue();

        EventShareLink hosts = liveLink(true);
        hosts.setCreatedBy(hostId);
        when(linkRepo.findByIdAndEventId(hosts.getId(), eventId)).thenReturn(Optional.of(hosts));
        assertThatThrownBy(() -> service.revoke(eventId, guestId, hosts.getId(), false))
                .isInstanceOf(ResponseStatusException.class);
    }

    /**
     * Closing a door is not the same as emptying the room, and emptying it is a host's act even
     * when the door was a guest's — the people it admitted are the event's members, not theirs.
     */
    @Test
    void aGuestCannotSweepOutTheMembersTheirLinkAdmitted() {
        stubMember(guestId, "INVITEE", "ACCEPTED");
        EventShareLink theirs = liveLink(false);
        theirs.setCreatedBy(guestId);
        when(linkRepo.findByIdAndEventId(theirs.getId(), eventId)).thenReturn(Optional.of(theirs));

        assertThatThrownBy(() -> service.revoke(eventId, guestId, theirs.getId(), true))
                .isInstanceOf(ResponseStatusException.class);
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private EventShareLink captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(EventShareLink.class);
        verify(linkRepo, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private EventMember captureMember() {
        var captor = org.mockito.ArgumentCaptor.forClass(EventMember.class);
        verify(memberRepo, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    private EventJoinRequest captureJoinRequest() {
        var captor = org.mockito.ArgumentCaptor.forClass(EventJoinRequest.class);
        verify(joinRequestRepo, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }
}
