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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Share links, and the claiming of them.
 *
 * <p>A link is a capability: holding a live token is what admits a non-member to an event. That
 * is a change from what came before, where the "link" was the event id and access was decided
 * entirely by the record's {@code visibility} field — which meant there was nothing to revoke,
 * and revoking it would have changed nothing.
 *
 * <p>Two rules run through everything here. The token is a secret, so only its hash is ever
 * stored and the raw value is returned exactly once. And a dead link is indistinguishable from
 * one that never existed, so an unknown, revoked, expired or exhausted token all produce the
 * same 404 — anything else confirms the event exists to precisely the person just cut off.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventShareLinkService {

    /**
     * 160 bits from a CSPRNG, base64url — 27 characters. Deliberately not a UUID: 122 bits, and
     * a shape that reads as an identifier, when the point of this change is that the token stops
     * being one.
     */
    private static final int TOKEN_BYTES = 20;
    private static final SecureRandom RANDOM = new SecureRandom();

    private final EventRepository          eventRepo;
    private final EventMemberRepository    memberRepo;
    private final EventShareLinkRepository linkRepo;
    private final EventJoinRequestRepository joinRequestRepo;

    // ── host-facing ──────────────────────────────────────────────────────────

    @Transactional
    public ShareLinkResponse create(UUID eventId, UUID requesterId, CreateShareLinkRequest req) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);

        Instant now = Instant.now();
        if (req.getExpiresAt() != null && !req.getExpiresAt().isAfter(now)) {
            throw new ValidationException("expiresAt must be in the future");
        }

        String token = newToken();

        EventShareLink link = new EventShareLink();
        link.setEventId(event.getId());
        link.setTokenHash(hash(token));
        link.setLabel(req.getLabel());
        link.setAutoAdmit(req.getAutoAdmit() == null || req.getAutoAdmit());
        link.setExpiresAt(req.getExpiresAt());
        link.setMaxUses(req.getMaxUses());
        link.setCreatedBy(requesterId);
        link.setCreatedAt(now);
        linkRepo.save(link);

        log.info("Minted share link {} for event {} by user={} (autoAdmit={})",
                link.getId(), eventId, requesterId, link.isAutoAdmit());

        // The one and only time the raw token leaves this service.
        ShareLinkResponse response = toResponse(link, now);
        response.setToken(token);
        return response;
    }

    public List<ShareLinkResponse> list(UUID eventId, UUID requesterId) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);
        Instant now = Instant.now();
        return linkRepo.findByEventIdOrderByCreatedAtDesc(event.getId()).stream()
                .map(l -> toResponse(l, now))
                .toList();
    }

    /**
     * Closes the door. The row survives so the audit trail does.
     *
     * @param removeJoined also removes the members this link admitted. Off by default and
     *                     deliberately explicit: someone who accepted three weeks ago is a member
     *                     now, and the link is how they arrived, not what keeps them here.
     *                     Revoking closes the door; it does not empty the room.
     */
    @Transactional
    public void revoke(UUID eventId, UUID requesterId, UUID linkId, boolean removeJoined) {
        Event event = requireEvent(eventId);
        requireManager(event, requesterId);

        EventShareLink link = linkRepo.findByIdAndEventId(linkId, event.getId())
                .orElseThrow(() -> new ResourceNotFoundException("EventShareLink", linkId.toString()));

        if (!link.isRevoked()) {
            link.setRevokedAt(Instant.now());
            link.setRevokedBy(requesterId);
            linkRepo.save(link);
        }

        if (removeJoined) {
            List<EventMember> admitted = memberRepo.findByJoinedViaShareLinkId(link.getId()).stream()
                    // The owner can never be swept out by this, whatever they arrived through.
                    .filter(m -> !m.getUserId().equals(event.getOwnerUserId()))
                    .filter(m -> !"REMOVED".equals(m.getStatus()))
                    .toList();
            for (EventMember m : admitted) {
                m.setStatus("REMOVED");
                m.setRemovedBy(requesterId);
                m.setRemovedAt(Instant.now());
            }
            memberRepo.saveAll(admitted);
            log.info("Revoked share link {} and removed {} members it admitted", linkId, admitted.size());
        } else {
            log.info("Revoked share link {} for event {} by user={}", linkId, eventId, requesterId);
        }
    }

    // ── token-facing ─────────────────────────────────────────────────────────

    /**
     * Resolves a raw token to its event, or 404s.
     *
     * <p>Unknown, revoked, expired and exhausted are one outcome on purpose. A distinct "this
     * link was revoked" would confirm the event exists to whoever is holding the dead token —
     * which is the person it was revoked from.
     */
    public EventShareLink resolve(String token) {
        Instant now = Instant.now();
        return linkRepo.findByTokenHash(hash(token))
                .filter(l -> l.isLive(now))
                .orElseThrow(() -> new ResourceNotFoundException("ShareLink", "token"));
    }

    /**
     * Redeems a link for the calling user.
     *
     * <p>Idempotent for someone who is already in: opening the same WhatsApp link twice is
     * ordinary, and the second tap must not be an error.
     */
    @Transactional
    public ClaimShareLinkResponse claim(String token, UUID userId) {
        EventShareLink link = resolve(token);
        Event event = requireEvent(link.getEventId());

        Optional<EventMember> existing = memberRepo.findByTenantIdAndUserId(event.getTenantId(), userId);
        Optional<EventMember> active = existing
                .filter(m -> !"REMOVED".equals(m.getStatus()) && !"DECLINED".equals(m.getStatus()));

        if (active.isPresent()) {
            return ClaimShareLinkResponse.builder()
                    .eventId(event.getId())
                    .outcome("ALREADY_MEMBER")
                    .role(active.get().getRole())
                    .build();
        }

        // Counted on redemption and nowhere else. Previews are the frequent case — every crawler
        // refresh hits one — and counting there would be a write on a read path.
        link.setUseCount(link.getUseCount() + 1);
        link.setLastUsedAt(Instant.now());
        linkRepo.save(link);

        if (!link.isAutoAdmit()) {
            return raiseJoinRequest(event, userId, link);
        }

        // A previously DECLINED or REMOVED row is revived in place rather than added alongside;
        // event_member is UNIQUE (tenant_id, user_id), and this mirrors how invite() behaves.
        EventMember member = existing.orElseGet(EventMember::new);
        member.setTenantId(event.getTenantId());
        member.setUserId(userId);
        member.setRole(link.getGrantsRole());
        member.setStatus("ACCEPTED");
        member.setJoinedViaShareLinkId(link.getId());
        member.setRemovedBy(null);
        member.setRemovedAt(null);
        memberRepo.save(member);

        log.info("User={} joined event {} via share link {}", userId, event.getId(), link.getId());
        return ClaimShareLinkResponse.builder()
                .eventId(event.getId())
                .outcome("JOINED")
                .role(member.getRole())
                .build();
    }

    /**
     * The non-auto-admit path: the link earns the holder a place in the queue, not in the event.
     *
     * <p>This has to be an event_join_request and emphatically not an INVITED member row.
     * respondToInvite() lets an INVITED member accept themselves — that is what the endpoint is
     * for — so parking them there would let the link holder wave themselves straight through and
     * make autoAdmit=false mean nothing at all.
     *
     * <p>A pending request is not attributed to the link it came from: approving one is a host
     * looking at a name and saying yes, so those members are deliberately outside the reach of
     * "revoke and remove" — a human vetted each of them individually.
     */
    private ClaimShareLinkResponse raiseJoinRequest(Event event, UUID userId, EventShareLink link) {
        Optional<EventJoinRequest> existing =
                joinRequestRepo.findByTenantIdAndUserId(event.getTenantId(), userId);

        // Opening the same link twice must not be an error, so an already-pending request is
        // reported rather than rejected the way requestToJoin() rejects it.
        if (existing.filter(r -> "PENDING".equals(r.getStatus())).isEmpty()) {
            EventJoinRequest jr = existing.orElseGet(EventJoinRequest::new);
            jr.setTenantId(event.getTenantId());
            jr.setUserId(userId);
            jr.setRequestedRole(link.getGrantsRole());
            jr.setStatus("PENDING");
            jr.setReviewedByUserId(null);
            jr.setReviewedAt(null);
            joinRequestRepo.save(jr);

            link.setUseCount(link.getUseCount() + 1);
            link.setLastUsedAt(Instant.now());
            linkRepo.save(link);
            log.info("User={} requested to join event {} via share link {}", userId, event.getId(), link.getId());
        }

        return ClaimShareLinkResponse.builder()
                .eventId(event.getId())
                .outcome("REQUESTED")
                .role(link.getGrantsRole())
                .build();
    }

    // ── internals ────────────────────────────────────────────────────────────

    static String newToken() {
        byte[] bytes = new byte[TOKEN_BYTES];
        RANDOM.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    /**
     * SHA-256, hex. Not BCrypt: this is a full-entropy random secret, so there is no dictionary
     * to slow an attacker down, and a deliberately slow hash would only tax the anonymous preview
     * path that resolves one on every crawler hit.
     */
    static String hash(String token) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            return HexFormat.of().formatHex(digest.digest(token.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private ShareLinkResponse toResponse(EventShareLink l, Instant now) {
        return ShareLinkResponse.builder()
                .id(l.getId())
                .label(l.getLabel())
                .grantsRole(l.getGrantsRole())
                .autoAdmit(l.isAutoAdmit())
                .status(l.status(now))
                .createdBy(l.getCreatedBy())
                .createdAt(l.getCreatedAt())
                .expiresAt(l.getExpiresAt())
                .maxUses(l.getMaxUses())
                .useCount(l.getUseCount())
                .lastUsedAt(l.getLastUsedAt())
                .revokedAt(l.getRevokedAt())
                .revokedBy(l.getRevokedBy())
                .build();
    }

    private Event requireEvent(UUID eventId) {
        return eventRepo.findById(eventId)
                .orElseThrow(() -> new ResourceNotFoundException("Event", eventId.toString()));
    }

    private void requireManager(Event event, UUID userId) {
        EventMember member = memberRepo
                .findByTenantIdAndUserIdAndStatusNot(event.getTenantId(), userId, "REMOVED")
                .orElseThrow(() -> new ResponseStatusException(HttpStatus.FORBIDDEN, "Not a member of this event"));
        if (!member.canManage()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "ADMIN or MAINTAINER role required");
        }
    }
}
