package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventInvitation;
import com.lagu.platform.event.domain.EventInvitationRepository;
import com.lagu.platform.event.domain.EventMember;
import com.lagu.platform.event.domain.EventMemberRepository;
import com.lagu.platform.event.domain.EventRepository;
import com.lagu.platform.event.client.RecordServiceClient;
import com.lagu.platform.event.client.UserServiceClient;
import com.lagu.platform.event.dto.EventInvitationResponse;
import com.lagu.platform.event.event.EventInvitationNotifier;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Inviting somebody who has no account yet, and admitting them when they get one.
 *
 * <p>{@link EventMemberService#invite} takes a user id, so the only people a host could ask along
 * were people this platform already had. A host planning a birthday knows their guests by email;
 * whether those guests have signed up is not something they should have to find out first.
 *
 * <p>An invitation is a promise, not a membership. It becomes one on {@link #claimFor}, at the
 * status {@code INVITED} — so the person who signs up is still asked whether they are coming,
 * rather than being silently counted as attending something they have not answered.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventInvitationService {

    /** Same set the member picker is limited to: nothing reachable from a form mints an ADMIN. */
    private static final Set<String> ASSIGNABLE = Set.of("INVITEE", "MAINTAINER");

    private final EventRepository eventRepo;
    private final EventMemberRepository memberRepo;
    private final EventInvitationRepository invitationRepo;
    private final EventMembershipGuard membership;
    private final RecordServiceClient recordClient;
    private final UserServiceClient userClient;
    private final EventInvitationNotifier notifier;

    // ── host-facing ──────────────────────────────────────────────────────────

    /** Records an invitation to an email address. Kept for the existing email-only call sites. */
    @Transactional
    public EventInvitationResponse invite(UUID eventId, UUID requesterId, String rawEmail, String role) {
        return invite(eventId, requesterId, rawEmail, null, null, role);
    }

    /**
     * Records an invitation to an email address or a phone number — exactly one of {@code rawEmail}
     * / {@code rawPhone} must be given.
     *
     * <p>The email path is told about the invitation by {@link EventInvitationNotifier}; the phone
     * path is not — this platform has no SMS sender anywhere, so there is nothing to stage that
     * would ever be delivered. A phone invitation still does something real: it sits pending until
     * that number is claimed (see {@link #claimFor}), because the security context can now carry a
     * verified phone (the gateway forwards {@code X-User-Phone} only when the caller's JWT phone
     * claim was verified). The host is responsible for telling the invitee some other way, same as
     * a share link.
     */
    @Transactional
    public EventInvitationResponse invite(UUID eventId, UUID requesterId, String rawEmail,
                                           String rawPhone, String rawCountryCode, String role) {
        Event event = membership.requireEvent(eventId);
        membership.requireManager(event, requesterId);

        boolean hasEmail = rawEmail != null && !rawEmail.isBlank();
        boolean hasPhone = rawPhone != null && !rawPhone.isBlank();
        if (hasEmail == hasPhone) {
            throw new ValidationException("Give either an email address or a phone number, not both or neither");
        }

        String grantedRole = role == null ? "INVITEE" : role.toUpperCase(Locale.ROOT);
        if (!ASSIGNABLE.contains(grantedRole)) {
            throw new ValidationException("Role must be one of " + ASSIGNABLE);
        }

        EventInvitation invitation = new EventInvitation();
        invitation.setTenantId(event.getTenantId());
        invitation.setEventId(eventId);
        invitation.setRole(grantedRole);
        invitation.setInvitedBy(requesterId);

        if (hasEmail) {
            String email = normaliseEmail(rawEmail);
            // Idempotent: a host clicking Invite twice must not produce two rows that both become
            // memberships, nor two lines for one person on the guest list. The partial unique
            // index in V9 is what makes this true under a race; this is what makes it pleasant.
            var existing = invitationRepo.findByEventIdAndEmailAndStatus(eventId, email, EventInvitation.PENDING);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
            invitation.setEmail(email);
        } else {
            String phone = normalisePhone(rawCountryCode, rawPhone);
            var existing = invitationRepo.findByEventIdAndPhoneAndStatus(eventId, phone, EventInvitation.PENDING);
            if (existing.isPresent()) {
                return toResponse(existing.get());
            }
            invitation.setPhone(phone);
            invitation.setCountryCode(onlyDigits(rawCountryCode).isEmpty() ? null : onlyDigits(rawCountryCode));
        }

        EventInvitation saved = invitationRepo.save(invitation);

        if (hasEmail) {
            // Staged in the same transaction as the row above, so the invitation and the email that
            // announces it commit together — see EventInvitationNotifier. Without this the feature
            // records an invitation nobody is ever told about.
            //
            // Resolving a recipientUserId is best-effort and never blocks the invite — see
            // UserServiceClient. Most invitees have no account yet, which is the whole point of
            // this feature; when one does, this is what lets their EVENT_INVITES preference apply.
            UUID recipientUserId = userClient.findUserIdByEmail(saved.getEmail()).orElse(null);
            notifier.invitationCreated(saved, eventNameOf(event), recipientUserId);
        }

        log.info("Invited {} to event {} as {} (invitation {})",
                hasEmail ? saved.getEmail() : "+" + saved.getPhone(), eventId, grantedRole, saved.getId());
        return toResponse(saved);
    }

    /**
     * The event's outstanding invitations.
     *
     * <p>Managers only, unlike the member list. These rows are contact details for people who are
     * not on the event and have not agreed to be listed on it — showing another guest an address
     * would publish something nobody handed over for that.
     */
    public List<EventInvitationResponse> listPending(UUID eventId, UUID requesterId) {
        Event event = membership.requireEvent(eventId);
        membership.requireManager(event, requesterId);
        return invitationRepo
                .findByEventIdAndStatusOrderByCreatedAtDesc(eventId, EventInvitation.PENDING)
                .stream().map(this::toResponse).toList();
    }

    @Transactional
    public void revoke(UUID eventId, UUID requesterId, UUID invitationId) {
        Event event = membership.requireEvent(eventId);
        membership.requireManager(event, requesterId);

        EventInvitation invitation = invitationRepo.findByIdAndEventId(invitationId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("EventInvitation", invitationId.toString()));

        if (!invitation.isPending()) {
            // Already claimed: the person is a member now, and removing them is the member list's
            // job. Revoking here would leave the membership and remove only the paper trail.
            throw new ResponseStatusException(HttpStatus.CONFLICT,
                    "This invitation has already been " + invitation.getStatus().toLowerCase(Locale.ROOT));
        }

        invitation.setStatus(EventInvitation.REVOKED);
        invitation.setRevokedBy(requesterId);
        invitation.setRevokedAt(Instant.now());
        invitationRepo.save(invitation);
    }

    // ── the claim ────────────────────────────────────────────────────────────

    /** Email-only call sites — see the phone-aware overload below. */
    @Transactional
    public int claimFor(UUID userId, String rawEmail) {
        return claimFor(userId, rawEmail, null);
    }

    /**
     * Turns every invitation waiting for this address or this phone into a membership.
     *
     * <p>Called with the contact details the gateway resolved for the signed-in caller — an email
     * from the JWT's {@code email} claim, a phone only when the JWT's {@code phoneVerified} claim
     * was true — never one the caller supplied on the request, because "name a contact and join
     * whatever it was invited to" is an account takeover with extra steps. The phone check is
     * strictly stronger than the email one for exactly that reason: email has no verification step
     * in this platform's sign-up flow, but a phone claim only exists on the JWT because OTP already
     * proved the caller holds that number.
     *
     * <p>Across every event, not one: somebody invited to three parties before they had an
     * account should get all three on the visit they finally sign up, rather than one per link
     * they happen to still have in their inbox.
     *
     * @return how many invitations were converted
     */
    @Transactional
    public int claimFor(UUID userId, String rawEmail, String rawVerifiedPhone) {
        if (userId == null) return 0;
        int claimed = 0;
        if (rawEmail != null && !rawEmail.isBlank()) {
            claimed += claimMatching(userId, invitationRepo.findByEmailAndStatus(
                    normaliseEmail(rawEmail), EventInvitation.PENDING));
        }
        if (rawVerifiedPhone != null && !rawVerifiedPhone.isBlank()) {
            String phone = onlyDigits(rawVerifiedPhone);
            if (!phone.isEmpty()) {
                claimed += claimMatching(userId, invitationRepo.findByPhoneAndStatus(
                        phone, EventInvitation.PENDING));
            }
        }
        return claimed;
    }

    private int claimMatching(UUID userId, List<EventInvitation> pending) {
        int claimed = 0;

        for (EventInvitation invitation : pending) {
            // Already on the event by some other route — a share link, a direct invite once they
            // had an account. The invitation is spent either way; what it must not do is
            // overwrite the membership they already have, which may be ACCEPTED.
            boolean alreadyMember = memberRepo
                    .findByTenantIdAndUserId(invitation.getTenantId(), userId)
                    .filter(m -> !"REMOVED".equals(m.getStatus()))
                    .isPresent();

            if (!alreadyMember) {
                EventMember member = new EventMember();
                member.setTenantId(invitation.getTenantId());
                member.setUserId(userId);
                member.setRole(invitation.getRole());
                // INVITED, not ACCEPTED: they were asked, and they still get to answer.
                member.setStatus("INVITED");
                member.setInvitedBy(invitation.getInvitedBy());
                member.setJoinedViaInvitationId(invitation.getId());
                memberRepo.save(member);
                claimed++;
            }

            invitation.setStatus(EventInvitation.CLAIMED);
            invitation.setClaimedBy(userId);
            invitation.setClaimedAt(Instant.now());
            invitationRepo.save(invitation);
        }

        if (!pending.isEmpty()) {
            log.info("User {} claimed {} of {} pending invitations", userId, claimed, pending.size());
        }
        return claimed;
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    /**
     * Lowercased and trimmed, because the claim is an equality match against what the identity
     * service holds. "Sam@Example.com " stored as typed is a row nobody can ever redeem.
     */
    private String normaliseEmail(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new ValidationException("An email address is required");
        }
        String email = raw.trim().toLowerCase(Locale.ROOT);
        if (!email.matches("^[^@\\s]+@[^@\\s]+\\.[^@\\s]+$")) {
            throw new ValidationException("That does not look like an email address");
        }
        return email;
    }

    /**
     * Digits only, country code folded in, so "+1 (555) 123-4567" typed on a form and
     * "+15551234567" read off a verified JWT claim normalise to the same value — the claim below
     * is an equality match against exactly that string. Kept separately from
     * {@link EventInvitation#getCountryCode()}, which is display-only.
     */
    private String normalisePhone(String rawCountryCode, String rawPhone) {
        String digits = onlyDigits(rawPhone);
        if (digits.isEmpty()) {
            throw new ValidationException("A phone number is required");
        }
        String cc = onlyDigits(rawCountryCode);
        // A caller who already typed the country code into the number itself (or pasted an
        // E.164 string) must not have it doubled by the separately-supplied dial code.
        String canonical = (!cc.isEmpty() && !digits.startsWith(cc)) ? cc + digits : digits;
        if (canonical.length() < 8 || canonical.length() > 15) {
            throw new ValidationException("That does not look like a phone number");
        }
        return canonical;
    }

    private String onlyDigits(String raw) {
        return raw == null ? "" : raw.replaceAll("[^0-9]", "");
    }

    /**
     * What the host called the event, for a subject line worth opening.
     *
     * <p>Best-effort: the name lives in the record, not on the event row, so this is a call to
     * record-service. An invitation must not fail because that call did — the email simply says
     * "an event" instead, which is a worse subject line and a working invitation.
     */
    private String eventNameOf(Event event) {
        try {
            Map<String, Object> record = recordClient.getRecord(event.getRecordId(), event.getTenantId());
            Object data = record == null ? null : record.get("data");
            if (data instanceof Map<?, ?> map) {
                Object name = map.get("name");
                return name instanceof String s ? s : null;
            }
        } catch (Exception e) {
            log.debug("Could not read the name of event {} for an invitation email: {}",
                    event.getId(), e.getMessage());
        }
        return null;
    }

    private EventInvitationResponse toResponse(EventInvitation i) {
        EventInvitationResponse r = new EventInvitationResponse();
        r.setId(i.getId());
        r.setEventId(i.getEventId());
        r.setContact(i.contact());
        r.setRole(i.getRole());
        r.setStatus(i.getStatus());
        r.setInvitedBy(i.getInvitedBy());
        r.setCreatedAt(i.getCreatedAt());
        return r;
    }
}
