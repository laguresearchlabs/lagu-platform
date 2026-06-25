package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class MetadataChangedEvent implements PlatformEvent {

    /**
     * ATTRIBUTE_CREATED | ATTRIBUTE_UPDATED | ATTRIBUTE_DELETED
     * ENTITY_CREATED | ENTITY_UPDATED | ENTITY_DELETED
     * OBJECT_TYPE_CREATED | OBJECT_TYPE_UPDATED | OBJECT_TYPE_DELETED
     */
    private String eventType;

    private UUID   resourceId;
    private String resourceName;
    private String resourceKind;   // ATTRIBUTE | ENTITY | OBJECT_TYPE
    private UUID   orgId;
    private UUID   changedBy;
    private Instant occurredAt;
}
