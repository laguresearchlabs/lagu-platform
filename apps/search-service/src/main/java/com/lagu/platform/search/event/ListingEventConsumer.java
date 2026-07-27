package com.lagu.platform.search.event;

import com.lagu.platform.events.ListingEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.search.service.IndexMappingBuilder;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.opensearch.client.opensearch.OpenSearchClient;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

/**
 * Maintains the cross-org consumer indexes ({@code platform-consumer-<objectType>}) from
 * listing-service's snapshot events. Only PUBLISHED snapshots ever enter these indexes, so
 * consumer search needs no status or org filtering — visibility is decided at publish time
 * by the workflow, not at query time.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class ListingEventConsumer {

    private final OpenSearchClient    osClient;
    private final IndexMappingBuilder mappingBuilder;

    @KafkaListener(
            topics = PlatformTopics.LISTING_EVENTS,
            groupId = "search-service",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.ListingEvent"}
    )
    public void handle(ListingEvent event, Acknowledgment ack) throws IOException {
        switch (event.getEventType()) {
            case "PUBLISHED"   -> index(event);
            case "UNPUBLISHED" -> delete(event);
            default -> { /* ignore */ }
        }
        ack.acknowledge();
    }

    private void index(ListingEvent event) throws IOException {
        String objectType = event.getObjectType();
        mappingBuilder.ensureConsumerIndex(objectType);
        String index = mappingBuilder.consumerIndexName(objectType);

        Map<String, Object> doc = new HashMap<>();
        doc.put("recordId",         event.getRecordId().toString());
        doc.put("tenantId",            event.getTenantId().toString());
        doc.put("objectType",       objectType);
        doc.put("status",           "PUBLISHED");
        doc.put("data",             event.getData() != null ? event.getData() : Map.of());
        doc.put("verificationTier", event.getVerificationTier());
        doc.put("searchBoost",      event.getSearchBoost() != null ? event.getSearchBoost() : 1.0);
        doc.put("publishedAt",      event.getPublishedAt() != null ? event.getPublishedAt().toString() : null);
        doc.put("updatedAt",        Instant.now().toString());

        osClient.index(r -> r.index(index).id(event.getRecordId().toString()).document(doc));
        log.debug("Indexed published listing {} into {}", event.getRecordId(), index);
    }

    private void delete(ListingEvent event) throws IOException {
        String index = mappingBuilder.consumerIndexName(event.getObjectType());
        osClient.delete(r -> r.index(index).id(event.getRecordId().toString()));
        log.debug("Removed listing {} from {}", event.getRecordId(), index);
    }
}
