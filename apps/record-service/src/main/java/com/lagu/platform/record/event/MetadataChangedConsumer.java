package com.lagu.platform.record.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.SchemaPublishedEvent;
import com.lagu.platform.record.client.MetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Evicts the local schema cache when schema-registry publishes a new schema version for a
 * listing type (schema-registry absorbed metadata-service's schema responsibilities — see
 * todo/13-no-code-vendor-platform-adr.md). Only published versions take effect for validation,
 * so eviction on SCHEMA_PUBLISHED (rather than every draft edit) matches schema-registry's
 * versioned-publish model.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataChangedConsumer {

    private final CacheManager cacheManager;

    @KafkaListener(
            topics = PlatformTopics.SCHEMA_EVENTS,
            groupId = "record-service-metadata",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.SchemaPublishedEvent"}
    )
    public void handle(SchemaPublishedEvent event, Acknowledgment ack) {
        Cache schemaCache = cacheManager.getCache(MetadataClient.SCHEMA_CACHE);
        if (schemaCache != null && "SCHEMA_PUBLISHED".equals(event.getEventType())) {
            schemaCache.evict(event.getListingType());
            log.info("Evicted schema cache for listing type '{}' (published v{})",
                    event.getListingType(), event.getVersion());
        }
        ack.acknowledge();
    }
}
