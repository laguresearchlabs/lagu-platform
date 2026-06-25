package com.lagu.platform.metadata.event;

import com.lagu.platform.events.MetadataChangedEvent;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.metadata.domain.AttributeDefinition;
import com.lagu.platform.metadata.domain.ObjectTypeDefinition;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class MetadataEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishAttributeCreated(AttributeDefinition def) {
        publish(def.getId().toString(), MetadataChangedEvent.builder()
                .eventType("ATTRIBUTE_CREATED")
                .resourceId(def.getId())
                .resourceName(def.getName())
                .resourceKind("ATTRIBUTE")
                .orgId(def.getOrgId())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishAttributeUpdated(AttributeDefinition def) {
        publish(def.getId().toString(), MetadataChangedEvent.builder()
                .eventType("ATTRIBUTE_UPDATED")
                .resourceId(def.getId())
                .resourceName(def.getName())
                .resourceKind("ATTRIBUTE")
                .orgId(def.getOrgId())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishObjectTypeCreated(ObjectTypeDefinition def) {
        publish(def.getId().toString(), MetadataChangedEvent.builder()
                .eventType("OBJECT_TYPE_CREATED")
                .resourceId(def.getId())
                .resourceName(def.getName())
                .resourceKind("OBJECT_TYPE")
                .orgId(def.getOrgId())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishObjectTypeUpdated(ObjectTypeDefinition def) {
        publish(def.getId().toString(), MetadataChangedEvent.builder()
                .eventType("OBJECT_TYPE_UPDATED")
                .resourceId(def.getId())
                .resourceName(def.getName())
                .resourceKind("OBJECT_TYPE")
                .orgId(def.getOrgId())
                .occurredAt(Instant.now())
                .build());
    }

    private void publish(String key, MetadataChangedEvent event) {
        kafkaTemplate.send(PlatformTopics.METADATA_CHANGED, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish MetadataChangedEvent type={}", event.getEventType(), ex);
                    }
                });
    }
}
