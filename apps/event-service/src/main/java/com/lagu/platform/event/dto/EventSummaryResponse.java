package com.lagu.platform.event.dto;

import com.lagu.platform.event.domain.Event;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

/**
 * Row shape for the platform-admin event listing (EventService.listForAdmin). Deliberately
 * excludes the schema-driven {@code data} map that EventResponse carries — serialising it per
 * row would put every event's full contents on an admin screen that only needs to identify
 * them; admins get the full map from GET /{eventId} when they open a specific event.
 *
 * <p>{@link #name} is the one exception, resolved per row from record-service. A table of bare
 * UUIDs isn't something anyone can moderate against, and the fetches run concurrently (see
 * EventService.listForAdmin) so the cost is one round trip, not one per row.
 */
@Data
@Builder
public class EventSummaryResponse {
    private UUID id;
    private UUID recordId;
    private String objectType;
    private UUID ownerUserId;
    private String status;
    /** The event's display name; null if its record couldn't be read. */
    private String name;
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
