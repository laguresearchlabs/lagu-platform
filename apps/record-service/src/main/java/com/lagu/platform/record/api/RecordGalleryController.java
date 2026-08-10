package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.record.client.MetadataClient;
import com.lagu.platform.record.client.MetadataClient.FieldSchemaDto;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordRepository;
import com.lagu.platform.record.dto.FileUploadUrlRequest;
import com.lagu.platform.record.dto.FileUploadUrlResponse;
import com.lagu.platform.record.dto.GalleryItemConfirmRequest;
import com.lagu.platform.record.dto.GalleryItemPatchRequest;
import com.lagu.platform.record.dto.GalleryItemResponse;
import com.lagu.platform.record.dto.GalleryReorderRequest;
import com.lagu.platform.common.media.GalleryItem;
import com.lagu.platform.record.service.MediaFieldRules;
import com.lagu.platform.record.service.RecordService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.PresignedUpload;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Photo galleries on records — an ordered set of images under one MEDIA_GALLERY field.
 *
 * <p>Separate from {@link RecordFileController} because the resource shape is different: a FILE
 * field holds one key and is replaced wholesale, while a gallery is a collection whose items are
 * added, captioned, reordered and removed individually. Sharing endpoints between the two would
 * have meant addressing gallery items by array index, which is not stable across a concurrent
 * reorder.
 *
 * <p>Uploads work exactly as they do for single files: bytes go straight from the client to the
 * bucket via a presigned URL, this service verifies the stored object at confirm time, and the
 * record's JSONB holds keys — never URLs, which are signed per request on read.
 *
 * <p>How many photos a gallery may hold, and of what kind, is admin configuration rather than
 * code; see {@link MediaFieldRules}.
 *
 * <p>Every mutation here is a read-modify-write of one JSONB column, unlike the single-file
 * confirm which just overwrites a value — so they are transactional, and {@code Record}'s
 * {@code @Version} turns two vendors editing the same gallery at once into a failed write for
 * one of them rather than a silently lost photo.
 */
@RestController
@RequestMapping("/api/v1/records/{id}/gallery/{fieldName}")
@RequiredArgsConstructor
@Slf4j
public class RecordGalleryController {

    private final RecordRepository recordRepository;
    private final RecordService recordService;
    private final MetadataClient metadataClient;
    private final StorageService storage;
    private final StorageProperties storageProperties;
    private final MediaIngest mediaIngest;

    /**
     * The gallery, in order, with a freshly signed URL per item.
     *
     * <p>One call for the whole gallery rather than one per photo: signing is per-object, and a
     * venue with twenty photos would otherwise be twenty round trips before anything renders.
     */
    @GetMapping
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<List<GalleryItemResponse>>> list(
            @PathVariable UUID id,
            @PathVariable String fieldName) {

        Record record = loadRecord(id);
        requireGalleryField(record, fieldName);

        List<GalleryItem> items = readGallery(record, fieldName, id);
        List<GalleryItemResponse> response = new ArrayList<>(items.size());
        for (int i = 0; i < items.size(); i++) {
            GalleryItem item = items.get(i);
            response.add(GalleryItemResponse.builder()
                    .id(item.id())
                    .url(storage.presignDownload(
                            item.fullKeyOrOriginal(), storageProperties.getDownloadUrlTtl()))
                    .thumbnailUrl(storage.presignDownload(
                            item.cardKeyOrOriginal(), storageProperties.getDownloadUrlTtl()))
                    .caption(item.caption())
                    .primary(item.primary())
                    .position(i)
                    .build());
        }
        return ResponseEntity.ok(ApiResponse.ok(response));
    }

    /**
     * Step 1 — a presigned PUT for a new gallery photo.
     *
     * <p>The gallery's size limit is checked here as well as at confirm. It is not authoritative
     * at this point (nothing stops two clients presigning against the same last slot), but
     * refusing an upload that is already over the limit beats letting a vendor transfer a photo
     * only to be told afterwards that it cannot be kept.
     */
    @PostMapping("/upload-url")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<ApiResponse<FileUploadUrlResponse>> requestUploadUrl(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @Valid @RequestBody FileUploadUrlRequest request) {

        Record record = loadRecord(id);
        FieldSchemaDto field = requireGalleryField(record, fieldName);

        requireRoomForOneMore(readGallery(record, fieldName, id), field, fieldName);

        String contentType = request.getContentType().toLowerCase();
        MediaFieldRules.policyFor(field)
                .checkDeclared(request.getFileName(), contentType, request.getSizeBytes());

        String key = StorageKeys.buildPending(storageProperties.getDomain(), id, request.getFileName());
        PresignedUpload upload = storage.presignUpload(
                key, contentType, storageProperties.getUploadUrlTtl());

        return ResponseEntity.ok(ApiResponse.ok(FileUploadUrlResponse.builder()
                .uploadUrl(upload.url())
                .key(upload.key())
                .contentType(upload.contentType())
                .expiresAt(upload.expiresAt())
                .build()));
    }

    /** Step 3 — verify the uploaded object and append it to the gallery. */
    @PostMapping("/items")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    @Transactional
    public ResponseEntity<ApiResponse<List<GalleryItemResponse>>> addItem(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @Valid @RequestBody GalleryItemConfirmRequest request) {

        Record record = loadRecord(id);
        FieldSchemaDto field = requireGalleryField(record, fieldName);

        List<GalleryItem> items = readGallery(record, fieldName, id);
        requireRoomForOneMore(items, field, fieldName);

        String key = requireUploadKey(request.getKey(), id);
        // Compared against the promoted form, because that is what a stored item holds — the
        // pending key it was uploaded to no longer exists by then. Comparing raw keys would let
        // a retried confirm add the same photo twice.
        String promoted = StorageKeys.promote(key);
        if (items.stream().anyMatch(existing -> existing.key().equals(promoted))) {
            throw new ValidationException("That photo is already in the gallery");
        }

        MediaIngest.Result ingested = verifyStoredObject(key, field, fieldName);

        items.add(new GalleryItem(UUID.randomUUID(), ingested.key(), request.getCaption(),
                request.isPrimary(),
                ingested.variantKeys().get(MediaIngest.CARD_VARIANT),
                ingested.variantKeys().get(MediaIngest.FULL_VARIANT)));
        save(record, fieldName, items);

        log.info("Added gallery item to record={} field={} key={}", id, fieldName, key);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toResponses(items)));
    }

    /** Sets a caption, or promotes an item to cover photo. */
    @PatchMapping("/items/{itemId}")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    @Transactional
    public ResponseEntity<ApiResponse<List<GalleryItemResponse>>> patchItem(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @PathVariable UUID itemId,
            @Valid @RequestBody GalleryItemPatchRequest request) {

        Record record = loadRecord(id);
        requireGalleryField(record, fieldName);

        List<GalleryItem> items = readGallery(record, fieldName, id);
        int index = indexOf(items, itemId);

        GalleryItem item = items.get(index);
        if (request.getCaption() != null) {
            item = item.withCaption(request.getCaption().isBlank() ? null : request.getCaption());
        }
        if (Boolean.TRUE.equals(request.getPrimary())) {
            // Demote the rest first — withSinglePrimary keeps the earliest primary it finds, so
            // promoting a later item would otherwise be silently undone by an earlier one.
            for (int i = 0; i < items.size(); i++) {
                items.set(i, items.get(i).withPrimary(false));
            }
            item = item.withPrimary(true);
        }
        items.set(index, item);

        save(record, fieldName, items);
        return ResponseEntity.ok(ApiResponse.ok(toResponses(items)));
    }

    /**
     * Removes a photo from the gallery and deletes the object behind it.
     *
     * <p>The object goes too: nothing else references it, and leaving it in the bucket would
     * leave a photo a vendor believes they deleted still readable to anything holding a signed
     * URL. The record is saved first, so a storage failure cannot leave the gallery pointing at
     * an object that is already gone.
     */
    @DeleteMapping("/items/{itemId}")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    @Transactional
    public ResponseEntity<ApiResponse<List<GalleryItemResponse>>> deleteItem(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @PathVariable UUID itemId) {

        Record record = loadRecord(id);
        requireGalleryField(record, fieldName);

        List<GalleryItem> items = readGallery(record, fieldName, id);
        GalleryItem removed = items.remove(indexOf(items, itemId));

        List<GalleryItem> saved = save(record, fieldName, items);
        // Derivatives go with the original — they are meaningless on their own, and leaving them
        // would keep a "deleted" photo renderable to anything holding a signed thumbnail URL.
        removed.allKeys().forEach(storage::delete);

        log.info("Removed gallery item from record={} field={} key={}", id, fieldName, removed.key());
        return ResponseEntity.ok(ApiResponse.ok(toResponses(saved)));
    }

    /** Reorders the gallery. The request must name every current item exactly once. */
    @PutMapping("/order")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    @Transactional
    public ResponseEntity<ApiResponse<List<GalleryItemResponse>>> reorder(
            @PathVariable UUID id,
            @PathVariable String fieldName,
            @Valid @RequestBody GalleryReorderRequest request) {

        Record record = loadRecord(id);
        requireGalleryField(record, fieldName);

        List<GalleryItem> items = readGallery(record, fieldName, id);
        Map<UUID, GalleryItem> byId = new HashMap<>();
        items.forEach(item -> byId.put(item.id(), item));

        // A duplicate id would otherwise clone one photo and silently drop another, so the
        // request is checked as a set before anything is applied.
        Set<UUID> requested = new LinkedHashSet<>(request.getItemIds());
        if (requested.size() != request.getItemIds().size() || !requested.equals(byId.keySet())) {
            throw new ValidationException(
                    "Reorder must list each of the gallery's " + items.size()
                            + " item(s) exactly once");
        }

        List<GalleryItem> reordered = new ArrayList<>(items.size());
        requested.forEach(itemId -> reordered.add(byId.get(itemId)));

        save(record, fieldName, reordered);
        return ResponseEntity.ok(ApiResponse.ok(toResponses(reordered)));
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private Record loadRecord(UUID id) {
        return recordService.findForContext(id, GatewayHeaderFilter.current());
    }

    /** The field must exist on the record's object type and be declared MEDIA_GALLERY. */
    private FieldSchemaDto requireGalleryField(Record record, String fieldName) {
        MetadataClient.ObjectTypeSchemaDto schema = metadataClient.getSchema(record.getObjectType());
        FieldSchemaDto field = schema.fields().stream()
                .filter(f -> f.name().equals(fieldName))
                .findFirst()
                .orElseThrow(() -> new ValidationException(
                        "Field '" + fieldName + "' is not defined for " + record.getObjectType()));
        if (!MediaFieldRules.TYPE_GALLERY.equals(field.type())) {
            throw new ValidationException("Field '" + fieldName + "' is not a gallery field");
        }
        return field;
    }

    /**
     * The gallery's current contents.
     *
     * <p>Every key is re-checked against the record's prefix on the way out. The value lives in
     * the record's JSONB, so validating only where this service writes would leave signing and
     * deletion driven by whatever is in the column.
     */
    private List<GalleryItem> readGallery(Record record, String fieldName, UUID recordId) {
        List<GalleryItem> items = GalleryItem.listFrom(record.getData().get(fieldName));
        items.forEach(item -> requireKeyBelongsTo(item.key(), recordId));
        return items;
    }

    private void requireRoomForOneMore(List<GalleryItem> items, FieldSchemaDto field, String fieldName) {
        int max = MediaFieldRules.maxCount(field);
        if (items.size() >= max) {
            throw new ValidationException(
                    "Gallery '" + fieldName + "' already holds its maximum of " + max + " photo(s)");
        }
    }

    /**
     * Verifies, scans, measures and promotes the uploaded object, and builds its derivatives.
     *
     * <p>Rejection deletes the object — it is already in the bucket, and would otherwise linger
     * unreferenced but still readable to anything holding a signed URL.
     */
    private MediaIngest.Result verifyStoredObject(String key, FieldSchemaDto field, String fieldName) {
        try {
            return mediaIngest.confirm(MediaIngest.Request.builder()
                    .pendingKey(key)
                    .policy(MediaFieldRules.policyFor(field))
                    .image(MediaFieldRules.imageConstraintsFor(field))
                    .derivatives(true)
                    .build());
        } catch (ValidationException e) {
            throw new ValidationException(
                    "Rejected upload for gallery '" + fieldName + "': " + e.getMessage());
        }
    }

    private void requireKeyBelongsTo(String key, UUID recordId) {
        if (!StorageKeys.isOwnedBy(key, storageProperties.getDomain(), recordId)) {
            throw new ValidationException("Key does not belong to record " + recordId);
        }
    }

    /**
     * A key a client may confirm: owned by this record, and still pending.
     *
     * <p>The pending check is what stops a caller naming an already-confirmed object and having
     * it re-adopted — those keys are durable and may already be referenced elsewhere in the
     * record.
     */
    private String requireUploadKey(String key, UUID recordId) {
        requireKeyBelongsTo(key, recordId);
        if (!StorageKeys.isPending(key)) {
            throw new ValidationException("Key is not an uploaded object awaiting confirmation");
        }
        return key;
    }

    private static int indexOf(List<GalleryItem> items, UUID itemId) {
        for (int i = 0; i < items.size(); i++) {
            if (items.get(i).id().equals(itemId)) return i;
        }
        throw new ResourceNotFoundException("Gallery item", itemId.toString());
    }

    /** Normalises the cover photo, writes the field, and returns what was stored. */
    private List<GalleryItem> save(Record record, String fieldName, List<GalleryItem> items) {
        List<GalleryItem> normalized = GalleryItem.withSinglePrimary(items);

        Map<String, Object> data = new HashMap<>(record.getData());
        data.put(fieldName, GalleryItem.toMaps(normalized));
        record.setData(data);

        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        record.setUpdatedBy(ctx != null ? ctx.getUserId() : null);
        recordRepository.save(record);
        return normalized;
    }

    /** Response shape without signed URLs — mutations return the new order, not fresh links. */
    private static List<GalleryItemResponse> toResponses(List<GalleryItem> items) {
        List<GalleryItem> normalized = GalleryItem.withSinglePrimary(items);
        List<GalleryItemResponse> response = new ArrayList<>(normalized.size());
        for (int i = 0; i < normalized.size(); i++) {
            GalleryItem item = normalized.get(i);
            response.add(GalleryItemResponse.builder()
                    .id(item.id())
                    .caption(item.caption())
                    .primary(item.primary())
                    .position(i)
                    .build());
        }
        return response;
    }
}
