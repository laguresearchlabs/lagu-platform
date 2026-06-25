package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class RecordEvent implements PlatformEvent {

    /**
     * CREATED | UPDATED | STATUS_CHANGED | DELETED
     * STATUS_TRANSITION_REQUESTED (consumed by workflow-service)
     */
    private String eventType;

    private UUID   recordId;
    private UUID   orgId;
    private String objectType;

    private String previousStatus;
    private String currentStatus;

    /** Populated on CREATED and UPDATED; null on STATUS_CHANGED / DELETED to reduce payload size. */
    private Map<String, Object> data;

    /** For STATUS_TRANSITION_REQUESTED only. */
    private String triggerName;
    private String comment;

    private UUID   changedBy;
    private Instant occurredAt;
}
