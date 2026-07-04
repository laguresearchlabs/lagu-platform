package com.lagu.platform.schema.event;

import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.SchemaPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Stages schema events in the transactional outbox ({@code schema_outbox}) inside the
 * caller's transaction; the shared relay delivers committed rows to Kafka. Publishing a
 * schema version and announcing it can no longer diverge.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaEventPublisher {

    private final TransactionalOutbox outbox;

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

        outbox.stage(PlatformTopics.SCHEMA_EVENTS, listingType, event);
    }
}
