package com.lagu.platform.event.service;

import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.common.exception.ValidationException;
import com.lagu.platform.event.domain.EventPhoto;
import com.lagu.platform.event.domain.EventPhotoRepository;
import com.lagu.platform.event.dto.EventPhotoResponse;
import com.lagu.platform.storage.ImageConstraints;
import com.lagu.platform.storage.MediaIngest;
import com.lagu.platform.storage.MediaPolicy;
import com.lagu.platform.storage.PresignedUpload;
import com.lagu.platform.storage.StorageKeys;
import com.lagu.platform.storage.StorageProperties;
import com.lagu.platform.storage.StorageService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

/**
 * The event photo album.
 *
 * <p>Same upload shape as everywhere else in the platform: presign, the client PUTs straight to
 * the bucket, then confirm — where {@link MediaIngest} verifies the bytes, scans them, builds
 * derivatives and promotes the object out of {@code pending/}. The file never passes through this
 * JVM, and only keys are stored.
 *
 * <p>This exists because an Event is not a record. Event <em>posts</em> got their photos for free
 * by being EVENT_POST records with a MEDIA_GALLERY field; an event itself has no record behind
 * it, so its album needs a table and these endpoints.
 */
@Service
@RequiredArgsConstructor
@Slf4j
@Transactional(readOnly = true)
public class EventPhotoService {

    /**
     * Photographs only. An album image is rendered by clients, and a signed bucket URL serves an
     * object with whatever Content-Type it was stored under — so SVG or HTML here is a stored-XSS
     * vector. Raster formats only, matching every other image surface in the platform.
     */
    private static final MediaPolicy POLICY = MediaPolicy.of(
            List.of("image/jpeg", "image/png", "image/webp"), 25);

    /** Album photos are displayed large, so a thumbnail-sized upload looks broken. */
    private static final ImageConstraints CONSTRAINTS =
            new ImageConstraints(480, 320, null, null);

    private final EventPhotoRepository repository;
    private final StorageService storage;
    private final StorageProperties storageProperties;
    private final MediaIngest mediaIngest;

    /** Step 1 — a presigned PUT. No row is created: an abandoned upload leaves only a pending
     *  object, which the bucket lifecycle rule sweeps, rather than a photo pointing at nothing. */
    public PresignedUpload requestUploadUrl(UUID eventId, String fileName, String contentType,
                                            long sizeBytes) {
        POLICY.checkDeclared(fileName, contentType, sizeBytes);
        String key = StorageKeys.buildPending(storageProperties.getDomain(), eventId, fileName);
        return storage.presignUpload(key, contentType.toLowerCase(),
                storageProperties.getUploadUrlTtl());
    }

    /** Step 3 — verify the uploaded object and add it to the album. */
    @Transactional
    public EventPhotoResponse confirmUpload(UUID eventId, UUID uploaderId, String pendingKey,
                                             String visibility, String caption) {
        // Keys are scoped to the event, so a member of one event cannot adopt another's object.
        if (!StorageKeys.isOwnedBy(pendingKey, storageProperties.getDomain(), eventId)) {
            throw new ValidationException("Key does not belong to event " + eventId);
        }
        if (!StorageKeys.isPending(pendingKey)) {
            throw new ValidationException("Key is not an uploaded object awaiting confirmation");
        }

        MediaIngest.Result ingested = mediaIngest.confirm(MediaIngest.Request.builder()
                .pendingKey(pendingKey)
                .policy(POLICY)
                .image(CONSTRAINTS)
                .derivatives(true)
                .build());

        EventPhoto photo = new EventPhoto();
        photo.setEventId(eventId);
        photo.setStorageKey(ingested.key());
        photo.setCardKey(ingested.variantKeys().get(MediaIngest.CARD_VARIANT));
        photo.setFullKey(ingested.variantKeys().get(MediaIngest.FULL_VARIANT));
        photo.setVisibility(normalizeVisibility(visibility));
        photo.setCaption(caption);
        photo.setUploadedBy(uploaderId);

        EventPhoto saved = repository.save(photo);
        log.info("Added photo {} to event {} ({})", saved.getId(), eventId, saved.getVisibility());
        return toResponse(saved);
    }

    /**
     * One event's album.
     *
     * @param visibility PUBLIC, PRIVATE, or null for everything. Callers that render to every
     *                   member must pass PUBLIC — this method does not infer it.
     */
    public List<EventPhotoResponse> list(UUID eventId, String visibility) {
        List<EventPhoto> photos = visibility == null
                ? repository.findByEventIdOrderByCreatedAtDesc(eventId)
                : repository.findByEventIdAndVisibilityOrderByCreatedAtDesc(
                        eventId, normalizeVisibility(visibility));
        return photos.stream().map(this::toResponse).toList();
    }

    /**
     * Removes a photo and the object behind it.
     *
     * <p>Row first, object second: a failed storage delete leaves an orphan, which is recoverable,
     * where the other order would leave a row pointing at bytes that are already gone.
     */
    @Transactional
    public void delete(UUID eventId, UUID photoId) {
        EventPhoto photo = repository.findByIdAndEventId(photoId, eventId)
                .orElseThrow(() -> new ResourceNotFoundException("EventPhoto", photoId.toString()));

        repository.delete(photo);
        deleteObjects(photo);
    }

    /**
     * Sweeps an event's photo objects. The rows themselves cascade with the event, but the
     * bucket does not know that — and by the time the rows are gone, so are the keys.
     */
    @Transactional
    public void deleteAllForEvent(UUID eventId) {
        repository.findByEventId(eventId).forEach(this::deleteObjects);
    }

    private void deleteObjects(EventPhoto photo) {
        for (String key : new String[]{photo.getStorageKey(), photo.getCardKey(), photo.getFullKey()}) {
            if (key == null) continue;
            try {
                storage.delete(key);
            } catch (RuntimeException e) {
                // Not fatal: the photo is already gone as far as every caller is concerned.
                // Logged because the object is now unreferenced and only this line says so.
                log.error("Deleted photo {} but could not remove object {}: {}",
                        photo.getId(), key, e.toString());
            }
        }
    }

    private String normalizeVisibility(String visibility) {
        if (visibility == null || visibility.isBlank()) return EventPhoto.PUBLIC;
        String upper = visibility.trim().toUpperCase();
        if (!EventPhoto.PUBLIC.equals(upper) && !EventPhoto.PRIVATE.equals(upper)) {
            throw new ValidationException("visibility must be PUBLIC or PRIVATE, got: " + visibility);
        }
        return upper;
    }

    /** Signs both URLs per response — never persisted, never cached. */
    private EventPhotoResponse toResponse(EventPhoto photo) {
        var ttl = storageProperties.getDownloadUrlTtl();
        return EventPhotoResponse.builder()
                .id(photo.getId())
                .url(storage.presignDownload(photo.fullKeyOrOriginal(), ttl))
                .thumbnailUrl(storage.presignDownload(photo.cardKeyOrOriginal(), ttl))
                .caption(photo.getCaption())
                .visibility(photo.getVisibility())
                .uploadedBy(photo.getUploadedBy())
                .createdAt(photo.getCreatedAt())
                .build();
    }
}
