package com.lagu.platform.booking.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

@Entity
@Table(name = "booking")
@Data
public class Booking {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "consumer_user_id", nullable = false)
    private UUID consumerUserId;

    /** VendorProfile.tenantId — that field already IS the public vendor id (see vendor-service). */
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    @Column(name = "listing_record_id", nullable = false)
    private UUID listingRecordId;

    /** Nullable — event-service Event.id, only set when the booking originated from an event. */
    @Column(name = "event_id")
    private UUID eventId;

    @Column(name = "event_date", nullable = false)
    private LocalDate eventDate;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private BookingStatus status = BookingStatus.INQUIRY;

    @Column(name = "inquiry_message", columnDefinition = "TEXT")
    private String inquiryMessage;

    @Column(name = "quoted_price", precision = 12, scale = 2)
    private BigDecimal quotedPrice;

    @Column(nullable = false, length = 3)
    private String currency = "INR";

    /**
     * Frozen from schema-registry's TierConfiguration.commissionRate at Quote time. A PERCENTAGE
     * (20.00 means 20%), matching TierConfiguration's own semantics — not a 0-1 fraction.
     */
    @Column(name = "commission_rate", precision = 5, scale = 2)
    private BigDecimal commissionRate;

    /** Frozen: quotedPrice * commissionRate at Quote time — never recomputed later. */
    @Column(name = "commission_amount", precision = 12, scale = 2)
    private BigDecimal commissionAmount;

    @Column(name = "quote_note", columnDefinition = "TEXT")
    private String quoteNote;

    @Column(name = "cancelled_by_user_id")
    private UUID cancelledByUserId;

    @Column(name = "cancel_reason", columnDefinition = "TEXT")
    private String cancelReason;

    @Column(name = "availability_claimed", nullable = false)
    private boolean availabilityClaimed = false;

    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() {
        updatedAt = Instant.now();
    }
}
