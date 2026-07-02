package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
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
