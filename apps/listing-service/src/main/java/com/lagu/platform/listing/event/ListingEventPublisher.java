package com.lagu.platform.listing.event;

import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.ListingEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.listing.domain.ListingAvailabilityRepository;
import com.lagu.platform.listing.domain.ListingSnapshot;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Stages listing events in the transactional outbox ({@code listing_outbox}) inside the
 * caller's transaction; the shared relay delivers committed rows to Kafka. Consumers
 * (search-service's consumer indexes) therefore stay exactly in step with the snapshot table.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListingEventPublisher {

    private final TransactionalOutbox outbox;
    private final ListingAvailabilityRepository availabilityRepo;

    /**
     * How far ahead the marketplace lets anyone search. Bounds the array on the event: a vendor
     * blocking out the next decade should not put ten years of dates on every one of their
     * listing documents.
     */
    private static final int HORIZON_MONTHS = 18;

    public void publishPublished(ListingSnapshot snap) {
        enqueue(snap.getRecordId(), snap.getTenantId(), ListingEvent.builder()
                .eventType("PUBLISHED")
                .recordId(snap.getRecordId())
                .tenantId(snap.getTenantId())
                .objectType(snap.getObjectType())
                .data(snap.getData())
                .verificationTier(snap.getVerificationTier())
                .searchBoost(snap.getSearchBoost() != null ? snap.getSearchBoost().doubleValue() : 1.0)
                .ratingAverage(snap.getRatingAverage() != null
                        ? snap.getRatingAverage().doubleValue() : null)
                .reviewCount(snap.getReviewCount())
                .unavailableDates(unavailableDates(snap.getRecordId()))
                .publishedAt(snap.getPublishedAt())
                .occurredAt(Instant.now())
                .build());
    }

    /**
     * The days this listing cannot take, from today to the search horizon.
     *
     * <p>Computed here rather than passed in, deliberately: every republish carries it, so there
     * is one code path and no "unchanged" case to get wrong. search-service indexes a whole
     * document rather than patching one, so a publish that omitted this would quietly mark every
     * booked date free again.
     *
     * <p>Past dates are dropped. Nobody searches for a venue for last Tuesday, and carrying the
     * history would make the array grow without bound for a listing that sells well.
     */
    private java.util.List<String> unavailableDates(java.util.UUID recordId) {
        java.time.LocalDate today = java.time.LocalDate.now();
        return availabilityRepo
                .findByRecordIdAndSlotDateBetween(recordId, today, today.plusMonths(HORIZON_MONTHS))
                .stream()
                .filter(a -> !"AVAILABLE".equals(a.getSlotType()))
                .map(a -> a.getSlotDate().toString())
                .sorted()
                .toList();
    }

    public void publishUnpublished(ListingSnapshot snap) {
        enqueue(snap.getRecordId(), snap.getTenantId(), ListingEvent.builder()
                .eventType("UNPUBLISHED")
                .recordId(snap.getRecordId())
                .tenantId(snap.getTenantId())
                .objectType(snap.getObjectType())
                .occurredAt(Instant.now())
                .build());
    }

    private void enqueue(UUID recordId, UUID tenantId, ListingEvent event) {
        outbox.stage(PlatformTopics.LISTING_EVENTS, tenantId + ":" + recordId, event);
    }
}
