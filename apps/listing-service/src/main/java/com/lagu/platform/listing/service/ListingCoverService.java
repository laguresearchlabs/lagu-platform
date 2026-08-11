package com.lagu.platform.listing.service;

import com.lagu.platform.common.media.GalleryItem;
import com.lagu.platform.listing.client.RecordServiceClient;
import com.lagu.platform.listing.client.RecordServiceClient.MediaKey;
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
 * Listing photos for consumers — a cover per listing for a results page, or a whole carousel for
 * one listing's detail page.
 *
 * <p>Both read the listing's <b>snapshot</b>, never its live record. A snapshot is the copy frozen
 * at approval, so a vendor's photos cannot reach a public page before anyone has reviewed them.
 * That is also what makes these endpoints safe to expose anonymously, where record-service's
 * gallery endpoint — gated on {@code RECORD:READ} — is not.
 *
 * <p>Signing stays on record-service, whose bucket credential is the one IAM-scoped to the
 * {@code record/} prefix. Widening this service's access to save a hop would dissolve exactly the
 * boundary that scoping exists to create, so keys go over an internal call instead — one call per
 * page or per carousel, not one per photo.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ListingCoverService {

    /** The gallery field on a listing type. Conventional across the seeded types; a listing
     *  whose schema names it something else simply has no photos here, rather than an error. */
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

    /** One photo as a consumer sees it. Deliberately the same shape as record-service's
     *  GalleryItemResponse, so a client renders either source with the same code. */
    @Data
    @Builder
    public static class Photo {
        private UUID id;
        private String url;
        private String thumbnailUrl;
        private String caption;
        private boolean primary;
        private int position;
    }

    // ── results page: one cover per listing ───────────────────────────────────

    public Map<UUID, Cover> coversFor(List<UUID> recordIds) {
        return coversFor(recordIds, DEFAULT_GALLERY_FIELD);
    }

    /**
     * @return a cover per listing that has one; listings with no gallery, or not visible to this
     *         caller, are simply absent
     */
    public Map<UUID, Cover> coversFor(List<UUID> recordIds, String galleryField) {
        if (recordIds == null || recordIds.isEmpty()) return Map.of();
        String field = fieldOrDefault(galleryField);

        // One query for the page, then one signing call — the whole point of the batch.
        // By recordId, not the entity's own id: findAllById would query the wrong column.
        List<ListingSnapshot> snapshots = snapshotRepository.findByRecordIdIn(recordIds);

        Map<UUID, GalleryItem> coverByRecord = new LinkedHashMap<>();
        List<MediaKey> keysToSign = new ArrayList<>();

        for (ListingSnapshot snapshot : snapshots) {
            // A listing the caller could not open is not one whose photo they get either —
            // otherwise this becomes a way to read an unpublished vendor's imagery by id.
            if (!ListingVisibility.isVisibleToCaller(snapshot)) continue;

            GalleryItem cover = coverOf(snapshot, field);
            if (cover == null) continue;
            coverByRecord.put(snapshot.getRecordId(), cover);
            keysToSign.add(new MediaKey(snapshot.getRecordId(), cover.cardKeyOrOriginal()));
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

    // ── detail page: the whole carousel for one listing ───────────────────────

    /**
     * Every photo on one listing, in order.
     *
     * <p>The public counterpart to record-service's gallery endpoint, which requires
     * {@code RECORD:READ} and is therefore unreachable to an anonymous consumer.
     *
     * @return the listing's photos, or empty when it has none, is not visible to this caller, or
     *         its gallery cannot be read
     */
    public List<Photo> photosFor(UUID recordId, String galleryField) {
        if (recordId == null) return List.of();
        String field = fieldOrDefault(galleryField);

        ListingSnapshot snapshot = snapshotRepository.findByRecordId(recordId).orElse(null);
        // Absent and not-visible deliberately look identical: an unpublished listing should not
        // be distinguishable from one that does not exist.
        if (snapshot == null || !ListingVisibility.isVisibleToCaller(snapshot)) return List.of();

        List<GalleryItem> items = readGallery(snapshot, field);
        if (items.isEmpty()) return List.of();

        // Every key travels with this listing's record id — repeated, because they all belong to
        // it — so the far side can verify each one before signing. One call for the carousel.
        List<MediaKey> keys = new ArrayList<>();
        for (GalleryItem item : items) {
            keys.add(new MediaKey(recordId, item.fullKeyOrOriginal()));
            keys.add(new MediaKey(recordId, item.cardKeyOrOriginal()));
        }
        Map<String, String> urls = recordServiceClient.signMediaKeys(keys);

        List<Photo> photos = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            GalleryItem item = items.get(i);
            String url = urls.get(item.fullKeyOrOriginal());
            // A photo whose display URL could not be signed is dropped rather than rendered as a
            // broken tile; the rest of the carousel still works.
            if (url == null) continue;
            String thumb = urls.get(item.cardKeyOrOriginal());
            photos.add(Photo.builder()
                    .id(item.id())
                    .url(url)
                    .thumbnailUrl(thumb != null ? thumb : url)
                    .caption(item.caption())
                    .primary(item.primary())
                    .position(i)
                    .build());
        }
        return photos;
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String fieldOrDefault(String galleryField) {
        return (galleryField == null || galleryField.isBlank()) ? DEFAULT_GALLERY_FIELD : galleryField;
    }

    /**
     * The snapshot's gallery, cover normalised, or empty when it has none.
     *
     * <p>Deliberately forgiving. This runs while rendering a public page over data written by
     * another service and frozen at some earlier schema version — a malformed gallery should cost
     * one listing its photos, not fail the request.
     */
    private List<GalleryItem> readGallery(ListingSnapshot snapshot, String field) {
        Map<String, Object> data = snapshot.getData();
        if (data == null) return List.of();
        try {
            List<GalleryItem> items = GalleryItem.listFrom(data.get(field));
            if (items.isEmpty()) return List.of();
            // Settles the cover the same way record-service does, so a snapshot frozen before
            // that normalisation existed still resolves to the same photo.
            return GalleryItem.withSinglePrimary(new ArrayList<>(items));
        } catch (RuntimeException e) {
            log.debug("Listing {} has no readable gallery in '{}': {}",
                    snapshot.getRecordId(), field, e.getMessage());
            return List.of();
        }
    }

    private GalleryItem coverOf(ListingSnapshot snapshot, String field) {
        List<GalleryItem> items = readGallery(snapshot, field);
        if (items.isEmpty()) return null;
        return items.stream().filter(GalleryItem::primary).findFirst().orElse(items.get(0));
    }
}
