package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotEmpty;
import lombok.Data;

import java.util.List;
import java.util.UUID;

/**
 * Reorders a gallery by listing every item id in the order wanted.
 *
 * <p>The whole order is sent rather than a move instruction: two vendors dragging photos at the
 * same time would otherwise interleave into an order neither chose, and a full list makes the
 * last write plainly win. It must name every current item exactly once — a partial list is
 * rejected rather than being taken as a hint about where the rest should land.
 */
@Data
public class GalleryReorderRequest {

    @NotEmpty
    private List<UUID> itemIds;
}
