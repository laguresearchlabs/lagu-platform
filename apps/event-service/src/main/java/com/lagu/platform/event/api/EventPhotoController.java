package com.lagu.platform.event.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.event.domain.Event;
import com.lagu.platform.event.domain.EventPhoto;
import com.lagu.platform.event.dto.ConfirmEventPhotoRequest;
import com.lagu.platform.event.dto.EventPhotoResponse;
import com.lagu.platform.event.dto.FileUploadUrlRequest;
import com.lagu.platform.event.service.EventMembershipGuard;
import com.lagu.platform.event.service.EventPhotoService;
import com.lagu.platform.storage.PresignedUpload;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * An event's photo album.
 *
 * <p>Bytes go straight from the client to the bucket via a presigned URL; this service never
 * handles them, and the album stores keys with URLs signed per response.
 *
 * <p>Separate from {@link EventPostController} because posts are EVENT_POST records and their
 * photos live in a MEDIA_GALLERY field served by record-service. An event is not a record, so its
 * album needs its own surface — this one.
 */
@RestController
@RequestMapping("/api/v1/events/{eventId}/photos")
@RequiredArgsConstructor
public class EventPhotoController {

    private final EventPhotoService photoService;
    private final EventMembershipGuard membership;

    /**
     * Step 1 — a presigned PUT.
     *
     * <p>Any accepted member, not just a manager. The album used to be manager-only on the
     * argument that it is curated — but the people at an event are the people holding the
     * cameras, and a guest's photographs could only reach the event as a post, which put the
     * pictures of the day in two places with two different permissions and no link between them.
     * What a guest cannot do is choose where it lands: see confirm.
     *
     * <p>No row is created here, so an unfinished upload leaves only a pending object for the
     * bucket lifecycle rule to sweep.
     */
    @PostMapping("/upload-url")
    public ResponseEntity<ApiResponse<Map<String, Object>>> requestUploadUrl(
            @PathVariable UUID eventId,
            @Valid @RequestBody FileUploadUrlRequest request) {
        Event event = membership.requireEvent(eventId);
        membership.requireMember(event, EventController.requireUserId());

        PresignedUpload upload = photoService.requestUploadUrl(
                eventId, request.getFileName(), request.getContentType(), request.getSizeBytes());

        return ResponseEntity.ok(ApiResponse.ok(Map.of(
                "uploadUrl", upload.url(),
                "key", upload.key(),
                "contentType", upload.contentType(),
                "expiresAt", upload.expiresAt())));
    }

    /**
     * Step 3 — verify the uploaded object and add it to the album.
     *
     * <p>The requested visibility is only honoured for a manager. A member's contribution is
     * PUBLIC whatever they ask for, because PRIVATE is the host's own shelf — the same asymmetry
     * the listing endpoint below already applies when reading.
     */
    @PostMapping
    public ResponseEntity<ApiResponse<EventPhotoResponse>> confirm(
            @PathVariable UUID eventId,
            @Valid @RequestBody ConfirmEventPhotoRequest request) {
        Event event = membership.requireEvent(eventId);
        UUID userId = EventController.requireUserId();
        boolean canManage = membership.requireMember(event, userId).canManage();

        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(
                photoService.confirmUpload(eventId, userId, request.getKey(),
                        request.getVisibility(), request.getCaption(), canManage)));
    }

    /**
     * The album.
     *
     * <p>A plain member only ever sees PUBLIC, whatever they ask for. The visibility parameter is
     * a manager's filter, not a member's — honouring it for everyone would make PRIVATE photos
     * one query string away.
     */
    @GetMapping
    public ResponseEntity<ApiResponse<List<EventPhotoResponse>>> list(
            @PathVariable UUID eventId,
            @RequestParam(required = false) String visibility) {
        Event event = membership.requireEvent(eventId);
        UUID userId = EventController.requireUserId();
        boolean canManage = membership.requireMember(event, userId).canManage();

        String effective = canManage ? visibility : EventPhoto.PUBLIC;
        return ResponseEntity.ok(ApiResponse.ok(photoService.list(eventId, effective)));
    }

    /** The uploader's own, or a manager's anything — enforced in the service, as posts do. */
    @DeleteMapping("/{photoId}")
    public ResponseEntity<Void> delete(@PathVariable UUID eventId, @PathVariable UUID photoId) {
        Event event = membership.requireEvent(eventId);
        UUID userId = EventController.requireUserId();
        boolean canManage = membership.requireMember(event, userId).canManage();

        photoService.delete(eventId, photoId, userId, canManage);
        return ResponseEntity.noContent().build();
    }
}
