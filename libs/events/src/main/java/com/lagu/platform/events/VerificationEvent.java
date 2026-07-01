package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class VerificationEvent implements PlatformEvent {

    /**
     * TIER_CHANGED | EXPIRED | REVOKED
     */
    private String eventType;

    private UUID   recordId;
    private UUID   orgId;
    private String objectType;
    private String previousTier;
    private String newTier;
    private UUID   changedBy;
    private Instant occurredAt;
}
