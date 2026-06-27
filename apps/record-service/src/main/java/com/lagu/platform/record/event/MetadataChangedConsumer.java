package com.lagu.platform.record.event;

import com.lagu.platform.events.MetadataChangedEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.record.client.MetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataChangedConsumer {

    private final CacheManager cacheManager;

    @KafkaListener(
            topics = PlatformTopics.METADATA_CHANGED,
            groupId = "record-service-metadata",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.MetadataChangedEvent"}
    )
    public void handle(MetadataChangedEvent event, Acknowledgment ack) {
        Cache schemaCache = cacheManager.getCache(MetadataClient.SCHEMA_CACHE);
        if (schemaCache != null) {
            if ("OBJECT_TYPE".equals(event.getResourceKind())) {
                schemaCache.evict(event.getResourceName());
                log.info("Evicted schema cache for object type '{}'", event.getResourceName());
            } else if ("ATTRIBUTE".equals(event.getResourceKind()) || "ENTITY".equals(event.getResourceKind())) {
                schemaCache.clear();
                log.info("Cleared full schema cache due to {} change: {}",
                        event.getResourceKind(), event.getResourceName());
            }
        }
        ack.acknowledge();
    }
}
