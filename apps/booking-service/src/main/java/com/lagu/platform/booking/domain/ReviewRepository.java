package com.lagu.platform.booking.domain;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface ReviewRepository extends JpaRepository<Review, UUID> {

    /** A listing's reviews, newest first — verified and not, since both are shown. */
    List<Review> findByListingRecordIdOrderByCreatedAtDesc(UUID listingRecordId);

    /** Everything said about one vendor, for their own reply queue. */
    List<Review> findByVendorIdOrderByCreatedAtDesc(UUID vendorId);

    /** Enforces the one-per-person-per-listing rule before the unique index has to. */
    Optional<Review> findByListingRecordIdAndAuthorUserId(UUID listingRecordId, UUID authorUserId);

    Optional<Review> findByBookingId(UUID bookingId);

    /**
     * The number behind a card's "4.8 (62)".
     *
     * <p>Verified rows only, deliberately: an unverified review is an opinion the platform cannot
     * stand behind, so it is shown but must not move the figure a shopper is trusting. Returns no
     * row at all for a listing with no verified reviews, which the caller reads as "unrated" —
     * distinct from a genuine average of zero, which cannot occur since the rating floor is 1.
     */
    @Query("""
        SELECT new com.lagu.platform.booking.domain.ReviewRepository$Aggregate(
                   AVG(r.rating), COUNT(r))
          FROM Review r
         WHERE r.listingRecordId = :listingRecordId AND r.verified = true
        """)
    Optional<Aggregate> aggregateVerified(@Param("listingRecordId") UUID listingRecordId);

    /** Average rating and how many verified reviews it was computed from. */
    record Aggregate(Double average, long count) {}
}
