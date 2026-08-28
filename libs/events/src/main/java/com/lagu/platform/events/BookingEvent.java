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
 * Emitted by booking-service on every lifecycle transition. automation-service consumes this
 * (see its AutomationSeeder) to raise notifications for both parties; search-service "my
 * bookings" and analytics remain possible future consumers of the same contract.
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

    /**
     * Which side of the booking {@link #changedBy} acted for — {@code CONSUMER} or {@code VENDOR}.
     *
     * Both parties can cancel and complete, so the actor alone does not say who needs telling.
     * A notification rule cannot work this out for itself: automation-service's ConditionEvaluator
     * compares a field to a constant, never one field to another, so "changedBy is not the
     * consumer" is not expressible there. Resolving it here — where consumerUserId is already in
     * hand — turns it into a plain {@code data.actorSide EQ CONSUMER} condition.
     */
    private String actorSide;

    /**
     * The vendor-org user to notify, resolved by booking-service from vendor-service at publish
     * time. Null when that lookup failed or the org has no active OWNER; the vendor-side triggers
     * carry an IS_NOT_NULL condition so they simply do not fire rather than addressing nobody.
     *
     * Deliberately one user, not the whole org: notification-service delivers to a single
     * recipientUserId and has no fan-out. See AutomationSeeder for what that costs.
     */
    private UUID vendorRecipientUserId;

    /**
     * Where to email the vendor — the org's business contact address from its VENDOR record, not
     * the owner's login address. Nullable: the field is optional on the VENDOR schema, and
     * notification-service treats a blank address as "in-app only" rather than an error.
     */
    private String vendorRecipientEmail;

    private Instant occurredAt;

    /** No {@code tenantId} field on this class — {@link PlatformEvent} is satisfied via vendorTenantId. */
    @Override
    public UUID getTenantId() {
        return vendorTenantId;
    }
}
