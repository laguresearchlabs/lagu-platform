package com.lagu.platform.listing.event;

import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.ListingEvent;
import com.lagu.platform.events.PlatformTopics;
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

    public void publishPublished(ListingSnapshot snap) {
        enqueue(snap.getRecordId(), snap.getOrgId(), ListingEvent.builder()
                .eventType("PUBLISHED")
                .recordId(snap.getRecordId())
                .orgId(snap.getOrgId())
                .objectType(snap.getObjectType())
                .data(snap.getData())
                .verificationTier(snap.getVerificationTier())
                .searchBoost(snap.getSearchBoost() != null ? snap.getSearchBoost().doubleValue() : 1.0)
                .publishedAt(snap.getPublishedAt())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishUnpublished(ListingSnapshot snap) {
        enqueue(snap.getRecordId(), snap.getOrgId(), ListingEvent.builder()
                .eventType("UNPUBLISHED")
                .recordId(snap.getRecordId())
                .orgId(snap.getOrgId())
                .objectType(snap.getObjectType())
                .occurredAt(Instant.now())
                .build());
    }

    private void enqueue(UUID recordId, UUID orgId, ListingEvent event) {
        outbox.stage(PlatformTopics.LISTING_EVENTS, orgId + ":" + recordId, event);
    }
}
