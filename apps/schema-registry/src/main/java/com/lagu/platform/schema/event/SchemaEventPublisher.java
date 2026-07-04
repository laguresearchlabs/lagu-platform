package com.lagu.platform.schema.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.SchemaPublishedEvent;
import com.lagu.platform.schema.domain.OutboxEvent;
import com.lagu.platform.schema.domain.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Stages schema events in the transactional outbox ({@code schema_outbox}) inside the
 * caller's transaction; {@link OutboxRelay} delivers committed rows to Kafka. Publishing a
 * schema version and announcing it can no longer diverge.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaEventPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void publishSchemaPublished(String listingType, int version,
                                       String changeClassification, String publishedBy) {
        // orgId is null for platform-level schema publishes
        SchemaPublishedEvent event = SchemaPublishedEvent.builder()
                .eventType("SCHEMA_PUBLISHED")
                .listingType(listingType)
                .version(version)
                .changeClassification(changeClassification)
                .publishedBy(publishedBy)
                .occurredAt(Instant.now())
                .build();

        OutboxEvent row = new OutboxEvent();
        row.setTopic(PlatformTopics.SCHEMA_EVENTS);
        row.setEventKey(listingType);
        row.setPayloadType(event.getClass().getName());
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Must propagate: failing to stage the event has to roll back the schema publish,
            // otherwise downstream schema caches silently miss the new version.
            throw new IllegalStateException("Could not serialize SchemaPublishedEvent for outbox", e);
        }
        outboxRepository.save(row);
    }
}
