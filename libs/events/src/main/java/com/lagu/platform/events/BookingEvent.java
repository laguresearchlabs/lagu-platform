package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * Emitted by booking-service on every lifecycle transition. No consumer is wired up yet (see
 * booking-service's README) — this exists so any future consumer (automation-service,
 * search-service "my bookings", analytics) has a stable contract to subscribe to.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class BookingEvent implements PlatformEvent {

    /**
     * Unique per logical publish, not per Kafka delivery attempt — same dedup-key convention as
     * {@link AutomationEvent#getEventId()}.
     */
    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    /** INQUIRED | QUOTED | CONFIRMED | COMPLETED | CANCELLED */
    private String eventType;

    private UUID bookingId;
    private UUID consumerUserId;

    /** The vendor org that owns the listing being booked — doubles as {@link #getTenantId()}. */
    private UUID vendorTenantId;

    private UUID listingRecordId;

    /** Nullable — event-service Event.id, only set when the booking originated from an event. */
    private UUID linkedEventId;

    private LocalDate eventDate;

    private String previousStatus;
    private String currentStatus;

    /** Set from QUOTED onward; null before a quote exists. */
    private BigDecimal quotedPrice;

    /** Frozen at Quote time; does not change if TierConfiguration changes later. */
    private BigDecimal commissionAmount;

    private UUID changedBy;
    private Instant occurredAt;

    /** No {@code tenantId} field on this class — {@link PlatformEvent} is satisfied via vendorTenantId. */
    @Override
    public UUID getTenantId() {
        return vendorTenantId;
    }
}
