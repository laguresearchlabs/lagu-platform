package com.lagu.platform.event.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * An invitation addressed to a contact rather than to a user.
 *
 * <p>{@link EventMember} keys on {@code userId}, so until now a host could only invite somebody
 * this platform already knew. That is not how anyone plans a party: guests are known by phone
 * number and email, and whether they have signed up is not the host's problem.
 *
 * <p>Deliberately not a member row with a null user id. Every read path in this service resolves
 * membership by user id — the guard, the posts feed, the photo album — and making that column
 * nullable would leave each of them quietly answerable for somebody who does not exist. This
 * <em>becomes</em> a membership when it is claimed, and stops being pending at that moment.
 */
@Entity
@Table(name = "event_invitation")
@Data
@NoArgsConstructor
public class EventInvitation {

    public static final String PENDING = "PENDING";
    public static final String CLAIMED = "CLAIMED";
    public static final String REVOKED = "REVOKED";

    @Id
    @GeneratedValue
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    /**
     * Exactly one of email or phone, normalised — lowercased address, digits-only number. The
     * claim is an equality match against what the identity service holds, so an unnormalised
     * value is a row nobody can ever redeem.
     */
    @Column(length = 320)
    private String email;

    @Column(length = 20)
    private String phone;

    @Column(name = "country_code", length = 8)
    private String countryCode;

    @Column(nullable = false, length = 30)
    private String role = "INVITEE"; // INVITEE | MAINTAINER

    @Column(nullable = false, length = 30)
    private String status = PENDING;

    @Column(name = "invited_by", nullable = false)
    private UUID invitedBy;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "claimed_by")
    private UUID claimedBy;

    @Column(name = "claimed_at")
    private Instant claimedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    public boolean isPending() {
        return PENDING.equals(status);
    }

    /**
     * What to show a host who is looking at a list of people they have asked along.
     *
     * <p>{@code phone} is stored digits-only with the country code already folded in (see
     * {@code EventInvitationService#normalisePhone}), so this puts the "+" back for anyone reading it.
     */
    public String contact() {
        if (email != null) return email;
        return phone != null ? "+" + phone : null;
    }
}
