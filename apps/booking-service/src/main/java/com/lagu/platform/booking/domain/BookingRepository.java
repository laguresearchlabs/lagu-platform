package com.lagu.platform.booking.domain;

import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BookingRepository extends JpaRepository<Booking, UUID> {

    List<Booking> findByConsumerUserIdOrderByCreatedAtDesc(UUID consumerUserId);

    List<Booking> findByConsumerUserIdAndEventIdOrderByCreatedAtDesc(UUID consumerUserId, UUID eventId);

    /** Every inquiry raised for one event, whoever raised it — see BookingService.listForEvent. */
    List<Booking> findByEventIdOrderByCreatedAtDesc(UUID eventId);

    /**
     * The vendor's own queue, and it must never include a SHORTLISTED row — that is a consumer
     * considering them, which they have not been told about and must not learn from a list. See
     * BookingStatus.SHORTLISTED.
     */
    List<Booking> findByVendorIdAndStatusNotOrderByCreatedAtDesc(UUID vendorId, BookingStatus excluded);

    /**
     * This consumer's most recent COMPLETED booking with one listing, if any.
     *
     * <p>What decides whether their review is verified. Asked for rather than supplied by the
     * client: verification is not a claim a caller gets to make about itself.
     */
    Optional<Booking> findFirstByConsumerUserIdAndListingRecordIdAndStatusOrderByUpdatedAtDesc(
            UUID consumerUserId, UUID listingRecordId, BookingStatus status);

    /** The admin settlement queue — oldest event first, since that is what has been owed longest. */
    List<Booking> findBySettlementStatusOrderByEventDateAsc(SettlementStatus settlementStatus);

    /** Unfiltered admin listing, most recent event first. */
    List<Booking> findAllByOrderByEventDateDesc();
}
