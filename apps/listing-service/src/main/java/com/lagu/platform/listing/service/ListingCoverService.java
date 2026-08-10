package com.lagu.platform.listing.service;

import com.lagu.platform.common.media.GalleryItem;
import com.lagu.platform.listing.client.RecordServiceClient;
import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.domain.ListingSnapshotRepository;
import lombok.Builder;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Cover photos for a page of listings, in one call.
 *
 * <p>A search results page shows twenty tiles, each wanting one thumbnail. Fetching them a
 * listing at a time is twenty round trips before the page has anything to show — the reason this
 * exists rather than clients calling the per-record gallery endpoint in a loop.
 *
 * <p>The keys come from each listing's <b>snapshot</b>, not from its live record. A snapshot is
 * the frozen copy taken at approval; resolving covers from the record instead would put photos
 * that have not been through approval onto a public page the moment a vendor uploaded them.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingCoverService {

    /** The gallery field on a listing type. Conventional across the seeded types; a listing
     *  whose schema names it something else simply has no cover here, rather than an error. */
    private static final String DEFAULT_GALLERY_FIELD = "gallery";

    private final ListingSnapshotRepository snapshotRepository;
    private final RecordServiceClient recordServiceClient;

    @Data
    @Builder
    public static class Cover {
        /** Card-sized, for a tile. Falls back to the original when no derivative exists. */
        private String thumbnailUrl;
        private String caption;
    }

    public Map<UUID, Cover> coversFor(List<UUID> recordIds) {
        return coversFor(recordIds, DEFAULT_GALLERY_FIELD);
    }

    /**
     * @return a cover per listing that has one; listings with no gallery are simply absent
     */
    public Map<UUID, Cover> coversFor(List<UUID> recordIds, String galleryField) {
        if (recordIds == null || recordIds.isEmpty()) return Map.of();

        String field = (galleryField == null || galleryField.isBlank())
                ? DEFAULT_GALLERY_FIELD : galleryField;

        // One query for the page, then one signing call — the whole point of the batch.
        // By recordId, not the entity's own id: findAllById would query the wrong column.
        List<ListingSnapshot> snapshots = snapshotRepository.findByRecordIdIn(recordIds);

        Map<UUID, GalleryItem> coverByRecord = new LinkedHashMap<>();
        Map<UUID, String> keysToSign = new LinkedHashMap<>();

        for (ListingSnapshot snapshot : snapshots) {
            // A listing the caller could not open is not one whose photo they get either —
            // otherwise this becomes a way to read an unpublished vendor's imagery by id.
            if (!ListingVisibility.isVisibleToCaller(snapshot)) continue;

            GalleryItem cover = coverOf(snapshot, field);
            if (cover == null) continue;
            coverByRecord.put(snapshot.getRecordId(), cover);
            keysToSign.put(snapshot.getRecordId(), cover.cardKeyOrOriginal());
        }

        Map<String, String> urls = recordServiceClient.signMediaKeys(keysToSign);

        Map<UUID, Cover> covers = new LinkedHashMap<>();
        coverByRecord.forEach((recordId, item) -> {
            String url = urls.get(item.cardKeyOrOriginal());
            if (url == null) return;   // refused or unavailable; the tile renders without a photo
            covers.put(recordId, Cover.builder().thumbnailUrl(url).caption(item.caption()).build());
        });
        return covers;
    }

    /**
     * The snapshot's cover photo, or null when it has no readable gallery.
     *
     * <p>Deliberately forgiving. This runs while rendering a public page over data written by
     * another service and frozen at some earlier schema version — a malformed gallery should cost
     * one tile its photo, not fail the search.
     */
    private GalleryItem coverOf(ListingSnapshot snapshot, String field) {
        Map<String, Object> data = snapshot.getData();
        if (data == null) return null;
        try {
            List<GalleryItem> items = GalleryItem.listFrom(data.get(field));
            if (items.isEmpty()) return null;
            // withSinglePrimary settles the cover the same way record-service does, so a snapshot
            // frozen before that normalisation existed still resolves to the same photo.
            List<GalleryItem> normalized = GalleryItem.withSinglePrimary(new ArrayList<>(items));
            return normalized.stream().filter(GalleryItem::primary).findFirst()
                    .orElse(normalized.get(0));
        } catch (RuntimeException e) {
            log.debug("Listing {} has no readable gallery in '{}': {}",
                    snapshot.getRecordId(), field, e.getMessage());
            return null;
        }
    }
}
