package com.lagu.platform.event.dto;

import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * A share link as its host sees it. Never carries the token except in the response to the call
 * that created it — the stored value is a hash, so there is nothing to return afterwards.
 */
@Data
@Builder
public class ShareLinkResponse {

    private UUID id;

    /**
     * The raw token, populated <b>only</b> by create and null on every other response. This is
     * the one moment it exists outside the recipient's URL bar.
     */
    private String token;

    private String label;
    private String grantsRole;
    private boolean autoAdmit;

    /** LIVE | REVOKED | EXPIRED | EXHAUSTED — the host is entitled to know which. */
    private String status;

    private UUID createdBy;
    private Instant createdAt;
    private Instant expiresAt;
    private Integer maxUses;
    private int useCount;
    private Instant lastUsedAt;
    private Instant revokedAt;
    private UUID revokedBy;
}
