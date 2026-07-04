package com.lagu.platform.listing.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.events.ListingEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.domain.OutboxEvent;
import com.lagu.platform.listing.domain.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Stages listing events in the transactional outbox ({@code listing_outbox}) inside the
 * caller's transaction; {@link OutboxRelay} delivers committed rows to Kafka. Consumers
 * (search-service's consumer indexes) therefore stay exactly in step with the snapshot table.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListingEventPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

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
        OutboxEvent row = new OutboxEvent();
        row.setTopic(PlatformTopics.LISTING_EVENTS);
        row.setEventKey(orgId + ":" + recordId);
        row.setPayloadType(event.getClass().getName());
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Must propagate: failing to stage the event has to roll back the snapshot change,
            // otherwise the consumer search index silently diverges from the snapshot table.
            throw new IllegalStateException("Could not serialize ListingEvent for outbox", e);
        }
        outboxRepository.save(row);
    }
}
