package com.lagu.platform.event.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One photo in an event's album.
 *
 * <p>Same shape as a listing photo and a post image, so clients render all three with one
 * component. Both URLs are short-lived signed links minted per response — never cache them.
 * {@code thumbnailUrl} is always populated, falling back to the original when the platform could
 * not build a derivative.
 */
@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class EventPhotoResponse {
    private UUID id;
    private String url;
    private String thumbnailUrl;
    private String caption;
    /** PUBLIC or PRIVATE. Managers see both; the overview widget shows PUBLIC only. */
    private String visibility;
    private UUID uploadedBy;
    private OffsetDateTime createdAt;
}
