package com.lagu.platform.event.dto;

import com.lagu.platform.event.domain.Event;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Row shape for the platform-admin event listing (EventService.listForAdmin). Deliberately
 * excludes the schema-driven {@code data} map that EventResponse carries — fetching it per
 * row would mean one record-service call per event on every page load; admins get the full
 * data map from GET /{eventId} when they open a specific event.
 */
@Data
@Builder
public class EventSummaryResponse {
    private UUID id;
    private UUID recordId;
    private String objectType;
    private UUID ownerUserId;
    private String status;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    public static EventSummaryResponse from(Event event) {
        return EventSummaryResponse.builder()
                .id(event.getId())
                .recordId(event.getRecordId())
                .objectType(event.getObjectType())
                .ownerUserId(event.getOwnerUserId())
                .status(event.getStatus())
                .createdAt(event.getCreatedAt().atOffset(ZoneOffset.UTC))
                .updatedAt(event.getUpdatedAt().atOffset(ZoneOffset.UTC))
                .build();
    }
}
