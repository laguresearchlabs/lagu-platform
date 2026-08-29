package com.lagu.platform.event.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

/**
 * One share link for an event — a capability, not a pointer.
 *
 * <p>Holding a live token is what admits a non-member. That is the whole reason this table
 * exists: before it, a share link was the event id and granted nothing, so there was nothing to
 * revoke and revoking it would have changed nothing.
 *
 * <p>Stores the token's SHA-256, never the token. The raw value is returned exactly once, at
 * creation, and is unrecoverable afterwards — a host who loses it revokes and mints another.
 */
@Entity
@Table(name = "event_share_link")
@Data
@NoArgsConstructor
public class EventShareLink {

    /** The only role a link may grant. See the ck_share_link_role constraint. */
    public static final String GRANTS_ROLE = "INVITEE";

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "event_id", nullable = false)
    private UUID eventId;

    @Column(name = "token_hash", nullable = false, length = 64)
    private String tokenHash;

    @Column(name = "grants_role", nullable = false, length = 30)
    private String grantsRole = GRANTS_ROLE;

    /** True: the link admits directly. False: it raises a join request for a host to approve. */
    @Column(name = "auto_admit", nullable = false)
    private boolean autoAdmit = true;

    @Column(length = 120)
    private String label;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    /** Null = never expires. */
    @Column(name = "expires_at")
    private Instant expiresAt;

    /** Null = unlimited. */
    @Column(name = "max_uses")
    private Integer maxUses;

    @Column(name = "use_count", nullable = false)
    private int useCount = 0;

    @Column(name = "last_used_at")
    private Instant lastUsedAt;

    @Column(name = "revoked_at")
    private Instant revokedAt;

    @Column(name = "revoked_by")
    private UUID revokedBy;

    public boolean isRevoked() {
        return revokedAt != null;
    }

    public boolean isExpired(Instant now) {
        return expiresAt != null && !expiresAt.isAfter(now);
    }

    public boolean isExhausted() {
        return maxUses != null && useCount >= maxUses;
    }

    /**
     * Whether this link still opens the door. The three ways it can stop doing so are deliberately
     * collapsed here and reported to callers as one indistinguishable outcome — see
     * EventShareLinkService.resolve().
     */
    public boolean isLive(Instant now) {
        return !isRevoked() && !isExpired(now) && !isExhausted();
    }

    /** For the host's own list, where the three are worth telling apart. */
    public String status(Instant now) {
        if (isRevoked())      return "REVOKED";
        if (isExpired(now))   return "EXPIRED";
        if (isExhausted())    return "EXHAUSTED";
        return "LIVE";
    }
}
