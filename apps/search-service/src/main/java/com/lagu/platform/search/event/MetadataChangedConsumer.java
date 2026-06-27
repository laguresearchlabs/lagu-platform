package com.lagu.platform.search.event;

import com.lagu.platform.events.MetadataChangedEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.search.client.MetadataClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            groupId = "search-service-metadata",
            properties = {"spring.json.value.default.type=com.lagu.platform.events.MetadataChangedEvent"}
    )
    public void handle(MetadataChangedEvent event, Acknowledgment ack) {
        if ("OBJECT_TYPE".equals(event.getResourceKind()) || "ATTRIBUTE".equals(event.getResourceKind())) {
            var cache = cacheManager.getCache(MetadataClient.SCHEMA_CACHE);
            if (cache != null) {
                cache.clear();
                log.info("Evicted search schema cache due to {} change: {}",
                        event.getResourceKind(), event.getResourceName());
            }
        }
        ack.acknowledge();
    }
}
