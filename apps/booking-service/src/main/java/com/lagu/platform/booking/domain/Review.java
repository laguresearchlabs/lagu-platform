package com.lagu.platform.booking.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

/**
 * A consumer's review of a vendor's listing.
 *
 * <p>It lives in booking-service, alongside the bookings, because the fact that makes a
 * marketplace rating worth anything is that the reviewer actually bought something — and only
 * this service knows whether they did. See V5__review.sql.
 */
@Entity
@Table(name = "review")
@Data
public class Review {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_record_id", nullable = false)
    private UUID listingRecordId;

    /** Denormalised from the listing so the vendor-side read is one query on one table. */
    @Column(name = "vendor_id", nullable = false)
    private UUID vendorId;

    /**
     * The COMPLETED booking this review came out of, when there is one. Null is the whole point:
     * a review without a booking is somebody who never bought through the platform, which is
     * allowed and marked {@link #verified} false.
     */
    @Column(name = "booking_id")
    private UUID bookingId;

    @Column(name = "author_user_id", nullable = false)
    private UUID authorUserId;

    @Column(nullable = false)
    private short rating;

    @Column(columnDefinition = "TEXT")
    private String body;

    /**
     * Frozen at write time and never recomputed: a booking cancelled afterwards does not
     * retroactively un-verify a review that was true when it was written. Only verified reviews
     * count toward the average a shopper sees.
     */
    @Column(nullable = false)
    private boolean verified = false;

    @Column(name = "vendor_reply", columnDefinition = "TEXT")
    private String vendorReply;

    @Column(name = "vendor_replied_at")
    private Instant vendorRepliedAt;

    @Column(name = "vendor_replied_by")
    private UUID vendorRepliedBy;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void touch() {
        this.updatedAt = Instant.now();
    }
}
