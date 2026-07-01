package com.lagu.platform.schema.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.SchemaPublishedEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class SchemaEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

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

        kafkaTemplate.send(PlatformTopics.SCHEMA_EVENTS, listingType, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish SchemaPublishedEvent for {}", listingType, ex);
                    } else {
                        log.debug("Published SchemaPublishedEvent: type={} version={}", listingType, version);
                    }
                });
    }
}
