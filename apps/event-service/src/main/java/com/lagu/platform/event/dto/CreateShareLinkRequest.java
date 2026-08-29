package com.lagu.platform.event.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.Size;
import lombok.Data;

import java.time.Instant;

@Data
public class CreateShareLinkRequest {

    /** Optional note for the host's own list — "family WhatsApp group". */
    @Size(max = 120)
    private String label;

    /**
     * Null defaults to true. False makes the link raise a join request instead of admitting
     * directly, which is the right choice for an event whose guest list is being curated.
     */
    private Boolean autoAdmit;

    /** Null = never expires. */
    private Instant expiresAt;

    /** Null = unlimited. */
    @Min(1)
    private Integer maxUses;
}
