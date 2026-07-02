package com.lagu.platform.search.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.SchemaPublishedEvent;
import com.lagu.platform.search.client.MetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Evicts the local schema cache when schema-registry publishes a new schema version (schema-registry
 * absorbed metadata-service's schema responsibilities — see todo/13-no-code-vendor-platform-adr.md).
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataChangedConsumer {

    private final CacheManager cacheManager;

    @KafkaListener(
            topics = PlatformTopics.SCHEMA_EVENTS,
            groupId = "search-service-metadata",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.SchemaPublishedEvent"}
    )
    public void handle(SchemaPublishedEvent event, Acknowledgment ack) {
        if ("SCHEMA_PUBLISHED".equals(event.getEventType())) {
            var cache = cacheManager.getCache(MetadataClient.SCHEMA_CACHE);
            if (cache != null) {
                cache.evict(event.getListingType());
                log.info("Evicted search schema cache for listing type '{}' (published v{})",
                        event.getListingType(), event.getVersion());
            }
        }
        ack.acknowledge();
    }
}
