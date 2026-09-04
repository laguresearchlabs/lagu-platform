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

    /** Average of the listing's verified reviews, or null when it has none — see ListingSnapshot. */
    private Double ratingAverage;

    /** How many verified reviews that average came from. */
    private Integer reviewCount;

    /**
     * The days this listing cannot take, as ISO dates — BOOKED or vendor-BLOCKED, from today
     * forward.
     *
     * <p>Sparse on purpose: a listing has an availability row only for a date somebody has
     * actually booked or blocked, so this is usually empty and never a 365-element calendar. That
     * is what makes it cheap enough to carry on every listing document and to filter a marketplace
     * by.
     *
     * <p>Always populated, never null-for-unchanged. search-service indexes a whole document
     * rather than patching one, so a republish that left this off would silently free up every
     * date the listing had taken.
     */
    private java.util.List<String> unavailableDates;

    private Instant publishedAt;
    private Instant occurredAt;
}
