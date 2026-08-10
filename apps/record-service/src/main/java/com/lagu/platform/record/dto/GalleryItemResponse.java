package com.lagu.platform.record.dto;

import lombok.Builder;
import lombok.Data;

import java.util.UUID;

/**
 * One gallery item as clients see it: the stored key never leaves the service, and the URL is
 * signed fresh for this response rather than read back out of the database.
 */
@Data
@Builder
public class GalleryItemResponse {

    private UUID id;

    /** Display-sized: the full derivative when one exists, otherwise the original. */
    private String url;

    /**
     * Card-sized, for tiles and carousel strips. Falls back to the original for formats the
     * platform cannot decode, so it is always populated — a client can use it unconditionally
     * rather than testing for null.
     */
    private String thumbnailUrl;

    private String caption;
    private boolean primary;

    /** Position in the gallery, so a client can render without relying on JSON array order. */
    private int position;
}
