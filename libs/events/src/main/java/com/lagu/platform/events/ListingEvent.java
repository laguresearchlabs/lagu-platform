package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Emitted by listing-service when a consumer-facing snapshot is (un)published.
 * search-service consumes these to maintain the cross-org consumer indexes
 * ({@code platform-consumer-<objectType>}) that back marketplace search.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class ListingEvent implements PlatformEvent {

    /** PUBLISHED | UNPUBLISHED */
    private String eventType;

    private UUID   recordId;
    private UUID   tenantId;
    private String objectType;

    /** Approved snapshot data; null on UNPUBLISHED. */
    private Map<String, Object> data;

    private String verificationTier;

    /** Tier-derived ranking multiplier, applied at query time via function_score. */
    private Double searchBoost;

    private Instant publishedAt;
    private Instant occurredAt;
}
