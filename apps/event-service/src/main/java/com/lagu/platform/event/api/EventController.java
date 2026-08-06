package com.lagu.platform.event.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.event.dto.CreateEventRequest;
import com.lagu.platform.event.dto.EventResponse;
import com.lagu.platform.event.dto.EventSummaryResponse;
import com.lagu.platform.event.dto.LinkVendorRequest;
import com.lagu.platform.event.dto.SharePreviewResponse;
import com.lagu.platform.event.dto.TransitionRequest;
import com.lagu.platform.event.dto.UpdateEventRequest;
import com.lagu.platform.event.service.EventService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/events")
@RequiredArgsConstructor
public class EventController {

    private final EventService eventService;

    @PostMapping
    public ResponseEntity<ApiResponse<EventResponse>> create(@Valid @RequestBody CreateEventRequest req) {
        UUID userId = requireUserId();
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(eventService.create(req, userId)));
    }

    /** Events the caller is an accepted member of. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<EventResponse>>> listMine() {
        return ResponseEntity.ok(ApiResponse.ok(eventService.listMine(requireUserId())));
    }

    /** Platform-admin listing across every event on the platform, regardless of membership. */
    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<PageResult<EventSummaryResponse>>> listForAdmin(
            @RequestParam(required = false) String objectType,
            @RequestParam(required = false) String status,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requirePlatformAdmin();
        return ResponseEntity.ok(ApiResponse.ok(eventService.listForAdmin(objectType, status, page, size)));
    }

    @GetMapping("/{eventId}")
    public ResponseEntity<ApiResponse<EventResponse>> get(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.get(eventId, requireUserId())));
    }

    /**
     * Unauthenticated link preview for a PUBLIC event — note the absence of requireUserId().
     * Opened up in application.yml's platform.security.public-paths because Open Graph
     * crawlers can't log in; the two-segment path keeps it clear of /{eventId} above, and
     * gateway-service already routes /api/v1/events/share/** without a JWT.
     */
    @GetMapping("/share/{eventId}")
    public ResponseEntity<ApiResponse<SharePreviewResponse>> sharePreview(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.getSharePreview(eventId)));
    }

    @PutMapping("/{eventId}")
    public ResponseEntity<ApiResponse<EventResponse>> update(@PathVariable UUID eventId,
                                                              @Valid @RequestBody UpdateEventRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.update(eventId, requireUserId(), req)));
    }

    @PostMapping("/{eventId}/transition")
    public ResponseEntity<ApiResponse<Void>> transition(@PathVariable UUID eventId,
                                                          @Valid @RequestBody TransitionRequest req) {
        eventService.requestTransition(eventId, requireUserId(), req);
        return ResponseEntity.accepted().body(ApiResponse.ok(null));
    }

    @PostMapping("/{eventId}/vendors")
    public ResponseEntity<ApiResponse<Void>> linkVendor(@PathVariable UUID eventId,
                                                         @Valid @RequestBody LinkVendorRequest req) {
        eventService.linkVendor(eventId, requireUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(null));
    }

    @GetMapping("/{eventId}/vendors")
    public ResponseEntity<ApiResponse<List<Map<String, Object>>>> listVendors(@PathVariable UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(eventService.listVendorLinks(eventId, requireUserId())));
    }

    @DeleteMapping("/{eventId}/vendors/{relationshipName}/{targetRecordId}")
    public ResponseEntity<Void> unlinkVendor(@PathVariable UUID eventId,
                                             @PathVariable String relationshipName,
                                             @PathVariable UUID targetRecordId) {
        eventService.unlinkVendor(eventId, requireUserId(), relationshipName, targetRecordId);
        return ResponseEntity.noContent().build();
    }

    static UUID requireUserId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return ctx.getUserId();
    }

    static void requirePlatformAdmin() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!ctx.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin role required");
        }
    }
}
