package com.lagu.platform.event.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.event.dto.ClaimShareLinkResponse;
import com.lagu.platform.event.dto.CreateShareLinkRequest;
import com.lagu.platform.event.dto.ShareLinkResponse;
import com.lagu.platform.event.service.EventShareLinkService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

/**
 * Managing an event's share links, and redeeming one.
 *
 * <p>Note where claim lives. The gateway opens {@code /api/v1/events/share/**} to unauthenticated
 * callers so link-preview crawlers can reach the preview endpoint — a {@code claim} sitting under
 * that prefix would have inherited anonymity, and claiming has to know who is claiming. It is
 * mounted outside {@code /share/} for exactly that reason; do not move it back.
 */
@RestController
@RequiredArgsConstructor
public class EventShareLinkController {

    private final EventShareLinkService shareLinkService;

    @PostMapping("/api/v1/events/{eventId}/share-links")
    public ResponseEntity<ApiResponse<ShareLinkResponse>> create(
            @PathVariable UUID eventId, @Valid @RequestBody(required = false) CreateShareLinkRequest req) {
        CreateShareLinkRequest body = req != null ? req : new CreateShareLinkRequest();
        ShareLinkResponse link = shareLinkService.create(eventId, EventController.requireUserId(), body);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(link));
    }

    @GetMapping("/api/v1/events/{eventId}/share-links")
    public ResponseEntity<ApiResponse<List<ShareLinkResponse>>> list(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(
                shareLinkService.list(eventId, EventController.requireUserId())));
    }

    /**
     * @param removeJoined also remove the members this link admitted. Defaults to false: closing
     *                     the door is not the same as emptying the room.
     */
    @DeleteMapping("/api/v1/events/{eventId}/share-links/{linkId}")
    public ResponseEntity<Void> revoke(@PathVariable UUID eventId,
                                       @PathVariable UUID linkId,
                                       @RequestParam(defaultValue = "false") boolean removeJoined) {
        shareLinkService.revoke(eventId, EventController.requireUserId(), linkId, removeJoined);
        return ResponseEntity.noContent().build();
    }

    /** Authenticated: the caller is the person being admitted. */
    @PostMapping("/api/v1/events/share-links/{token}/claim")
    public ResponseEntity<ApiResponse<ClaimShareLinkResponse>> claim(@PathVariable String token) {
        return ResponseEntity.ok(ApiResponse.ok(
                shareLinkService.claim(token, EventController.requireUserId())));
    }
}
