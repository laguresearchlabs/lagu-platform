package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventInvitation;
import com.lagu.platform.event.domain.EventInvitationRepository;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.client.RecordServiceClient;
import com.lagu.platform.event.client.UserServiceClient;
import com.lagu.platform.event.event.EventInvitationNotifier;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.*;

/**
 * Inviting somebody who has no account yet.
 *
 * <p>The claim is the part worth holding still: it turns a promise into a membership, and it must
 * key off the address the gateway resolved for the session rather than anything a caller can say
 * about themselves — otherwise it is "name an email, join whatever it was invited to".
 */
class EventInvitationServiceTest {

    private final EventRepository eventRepo = mock(EventRepository.class);
    private final EventMemberRepository memberRepo = mock(EventMemberRepository.class);
    private final EventInvitationRepository invitationRepo = mock(EventInvitationRepository.class);
    private final EventMembershipGuard membership = mock(EventMembershipGuard.class);
    private final RecordServiceClient recordClient = mock(RecordServiceClient.class);
    private final UserServiceClient userClient = mock(UserServiceClient.class);
    private final EventInvitationNotifier notifier = mock(EventInvitationNotifier.class);

    private final EventInvitationService service = new EventInvitationService(
            eventRepo, memberRepo, invitationRepo, membership, recordClient, userClient, notifier);

    private final UUID eventId = UUID.randomUUID();
    /** Event.getTenantId() is derived from the id — there is no separate column. */
    private final UUID tenantId = eventId;
    private final UUID hostId = UUID.randomUUID();
    private final UUID newUserId = UUID.randomUUID();

    private Event event;

    @BeforeEach
    void setUp() {
        event = new Event();
        event.setId(eventId);
        when(membership.requireEvent(eventId)).thenReturn(event);
        when(invitationRepo.save(any())).thenAnswer(inv -> inv.getArgument(0));
        when(invitationRepo.findByEventIdAndEmailAndStatus(any(), any(), any())).thenReturn(Optional.empty());
    }

    @Test
    void storesTheAddressLowercasedAndTrimmed() {
        service.invite(eventId, hostId, "  Sam@Example.COM ", null);

        assertThat(captureSaved().getEmail()).isEqualTo("sam@example.com");
    }

    /** The claim is an equality match, so an unnormalised row is one nobody could ever redeem. */
    @Test
    void refusesSomethingThatIsNotAnAddress() {
        assertThatThrownBy(() -> service.invite(eventId, hostId, "not-an-email", null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void refusesToMintAnOrganizerFromAForm() {
        assertThatThrownBy(() -> service.invite(eventId, hostId, "sam@example.com", "ADMIN"))
                .isInstanceOf(ValidationException.class);
    }

    /** Clicking Invite twice must not put the same person on the guest list twice. */
    @Test
    void invitingTheSameAddressAgainReturnsTheInvitationAlreadyStanding() {
        EventInvitation existing = pending("sam@example.com");
        when(invitationRepo.findByEventIdAndEmailAndStatus(eventId, "sam@example.com", "PENDING"))
                .thenReturn(Optional.of(existing));

        var response = service.invite(eventId, hostId, "sam@example.com", null);

        assertThat(response.getId()).isEqualTo(existing.getId());
        verify(invitationRepo, never()).save(any());
    }

    // ── inviting by phone ───────────────────────────────────────────────────────

    @Test
    void invitingByPhoneFoldsTheCountryCodeInAndStripsFormatting() {
        service.invite(eventId, hostId, null, "(555) 123-4567", "+1", null);

        assertThat(captureSaved().getPhone()).isEqualTo("15551234567");
    }

    /** A caller who pastes an already-E.164 number must not get the dial code doubled. */
    @Test
    void invitingByPhoneDoesNotDoubleACountryCodeAlreadyInTheNumber() {
        service.invite(eventId, hostId, null, "+15551234567", "+1", null);

        assertThat(captureSaved().getPhone()).isEqualTo("15551234567");
    }

    @Test
    void refusesNeitherAnEmailNorAPhone() {
        assertThatThrownBy(() -> service.invite(eventId, hostId, null, null, null, null))
                .isInstanceOf(ValidationException.class);
    }

    @Test
    void refusesBothAnEmailAndAPhone() {
        assertThatThrownBy(() -> service.invite(eventId, hostId, "sam@example.com", "5551234567", "+1", null))
                .isInstanceOf(ValidationException.class);
    }

    /** Nothing to text it to — this platform sends no SMS, so a phone invitation stages no email. */
    @Test
    void invitingByPhoneNeverStagesAnEmail() {
        service.invite(eventId, hostId, null, "5551234567", "+1", null);

        verifyNoInteractions(notifier);
    }

    @Test
    void invitingTheSamePhoneAgainReturnsTheInvitationAlreadyStanding() {
        EventInvitation existing = pending(null);
        existing.setPhone("15551234567");
        when(invitationRepo.findByEventIdAndPhoneAndStatus(eventId, "15551234567", "PENDING"))
                .thenReturn(Optional.of(existing));

        var response = service.invite(eventId, hostId, null, "5551234567", "+1", null);

        assertThat(response.getId()).isEqualTo(existing.getId());
        verify(invitationRepo, never()).save(any());
    }

    // ── the claim ────────────────────────────────────────────────────────────

    /** Only a phone the gateway vouched for as verified may redeem someone else's invitation. */
    @Test
    void claimingByPhoneMatchesOnTheSameDigitsOnlyForm() {
        EventInvitation invitation = pending(null);
        invitation.setPhone("15551234567");
        when(invitationRepo.findByPhoneAndStatus("15551234567", "PENDING"))
                .thenReturn(List.of(invitation));
        when(memberRepo.findByTenantIdAndUserId(tenantId, newUserId)).thenReturn(Optional.empty());

        assertThat(service.claimFor(newUserId, null, "+1 (555) 123-4567")).isEqualTo(1);
        assertThat(invitation.getStatus()).isEqualTo("CLAIMED");
    }

    @Test
    void claimingClaimsBothAnEmailAndAVerifiedPhoneInOneCall() {
        EventInvitation byEmail = pending("sam@example.com");
        EventInvitation byPhone = pending(null);
        byPhone.setPhone("15551234567");
        when(invitationRepo.findByEmailAndStatus("sam@example.com", "PENDING")).thenReturn(List.of(byEmail));
        when(invitationRepo.findByPhoneAndStatus("15551234567", "PENDING")).thenReturn(List.of(byPhone));
        when(memberRepo.findByTenantIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThat(service.claimFor(newUserId, "sam@example.com", "+15551234567")).isEqualTo(2);
    }

    @Test
    void aSessionWithNoVerifiedPhoneClaimsNothingByPhone() {
        assertThat(service.claimFor(newUserId, null, null)).isZero();
        verifyNoInteractions(invitationRepo);
    }

    @Test
    void claimingTurnsAnInvitationIntoAMembershipTheyStillHaveToAnswer() {
        EventInvitation invitation = pending("sam@example.com");
        when(invitationRepo.findByEmailAndStatus("sam@example.com", "PENDING"))
                .thenReturn(List.of(invitation));
        when(memberRepo.findByTenantIdAndUserId(tenantId, newUserId)).thenReturn(Optional.empty());

        assertThat(service.claimFor(newUserId, "Sam@Example.com")).isEqualTo(1);

        var captor = org.mockito.ArgumentCaptor.forClass(EventMember.class);
        verify(memberRepo).save(captor.capture());
        EventMember created = captor.getValue();
        // INVITED, not ACCEPTED — they were asked, and they still get to answer.
        assertThat(created.getStatus()).isEqualTo("INVITED");
        assertThat(created.getUserId()).isEqualTo(newUserId);
        assertThat(created.getJoinedViaInvitationId()).isEqualTo(invitation.getId());
        assertThat(invitation.getStatus()).isEqualTo("CLAIMED");
    }

    /**
     * Somebody already on the event by another route — a share link, or a direct invite once they
     * had an account. The invitation is spent, but it must not overwrite a membership that may
     * already be ACCEPTED.
     */
    @Test
    void claimingDoesNotDisturbAMembershipTheyAlreadyHave() {
        EventInvitation invitation = pending("sam@example.com");
        when(invitationRepo.findByEmailAndStatus("sam@example.com", "PENDING"))
                .thenReturn(List.of(invitation));
        EventMember already = new EventMember();
        already.setStatus("ACCEPTED");
        when(memberRepo.findByTenantIdAndUserId(tenantId, newUserId)).thenReturn(Optional.of(already));

        assertThat(service.claimFor(newUserId, "sam@example.com")).isZero();

        verify(memberRepo, never()).save(any());
        assertThat(invitation.getStatus()).isEqualTo("CLAIMED");
    }

    /**
     * Invited to three parties before signing up, all three arrive on the visit they finally do —
     * not one per link they happen to still have in their inbox.
     */
    @Test
    void claimsEveryEventWaitingForThatAddress() {
        when(invitationRepo.findByEmailAndStatus("sam@example.com", "PENDING"))
                .thenReturn(List.of(pending("sam@example.com"), pending("sam@example.com")));
        when(memberRepo.findByTenantIdAndUserId(any(), any())).thenReturn(Optional.empty());

        assertThat(service.claimFor(newUserId, "sam@example.com")).isEqualTo(2);
    }

    @Test
    void aSessionWithNoAddressClaimsNothing() {
        assertThat(service.claimFor(newUserId, null)).isZero();
        assertThat(service.claimFor(newUserId, "  ")).isZero();
        verifyNoInteractions(invitationRepo);
    }

    private EventInvitation pending(String email) {
        EventInvitation i = new EventInvitation();
        i.setId(UUID.randomUUID());
        i.setEventId(eventId);
        i.setTenantId(tenantId);
        i.setEmail(email);
        i.setRole("INVITEE");
        i.setInvitedBy(hostId);
        return i;
    }

    private EventInvitation captureSaved() {
        var captor = org.mockito.ArgumentCaptor.forClass(EventInvitation.class);
        verify(invitationRepo, atLeastOnce()).save(captor.capture());
        return captor.getValue();
    }

    // ── the email ────────────────────────────────────────────────────────────

    /**
     * An invitation nobody is told about is not an invitation. The invitee has no account, no
     * session and no reason to visit, so this email is the only thing that reaches them.
     */
    @Test
    void tellsTheInviteeAboutIt() {
        when(recordClient.getRecord(any(), any()))
                .thenReturn(Map.of("data", Map.of("name", "Priya's 30th")));

        service.invite(eventId, hostId, "sam@example.com", null);

        verify(notifier).invitationCreated(any(EventInvitation.class), eq("Priya's 30th"), isNull());
    }

    /** The whole point of UserServiceClient: an invitee who already has an account gets attributed. */
    @Test
    void attributesTheEmailToAnExistingAccountWhenOneMatches() {
        UUID existingUserId = UUID.randomUUID();
        when(userClient.findUserIdByEmail("sam@example.com")).thenReturn(Optional.of(existingUserId));

        service.invite(eventId, hostId, "sam@example.com", null);

        verify(notifier).invitationCreated(any(EventInvitation.class), any(), eq(existingUserId));
    }

    /** Re-inviting an address already standing must not send them a second email. */
    @Test
    void doesNotEmailAgainForAnInvitationAlreadyStanding() {
        when(invitationRepo.findByEventIdAndEmailAndStatus(eventId, "sam@example.com", "PENDING"))
                .thenReturn(Optional.of(pending("sam@example.com")));

        service.invite(eventId, hostId, "sam@example.com", null);

        verifyNoInteractions(notifier);
    }

    /**
     * The name lives in the record, not on the event row, so fetching it is a call to another
     * service. An invitation must not fail because that call did — a worse subject line is not a
     * reason to lose the invitation.
     */
    @Test
    void stillInvitesWhenTheEventsNameCannotBeRead() {
        when(recordClient.getRecord(any(), any())).thenThrow(new RuntimeException("record-service down"));

        service.invite(eventId, hostId, "sam@example.com", null);

        verify(notifier).invitationCreated(any(EventInvitation.class), isNull(), isNull());
    }
}