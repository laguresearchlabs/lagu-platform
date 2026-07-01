package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class SchemaPublishedEvent implements PlatformEvent {

    /**
     * Always "SCHEMA_PUBLISHED".
     */
    private String eventType;

    private String listingType;
    private int    version;

    /** SAFE | SOFT_BREAKING | HARD_BREAKING */
    private String changeClassification;

    private String publishedBy;
    private java.util.UUID orgId;    // null = platform-level publish
    private Instant occurredAt;
}
