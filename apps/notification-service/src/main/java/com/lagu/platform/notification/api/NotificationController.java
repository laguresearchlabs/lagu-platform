package com.lagu.platform.notification.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.notification.domain.NotificationCategory;
import com.lagu.platform.notification.dto.NotificationDto;
import com.lagu.platform.notification.dto.NotificationPreferenceDto;
import com.lagu.platform.notification.dto.UpdateNotificationPreferencesRequest;
import com.lagu.platform.notification.service.NotificationPreferenceService;
import com.lagu.platform.notification.service.NotificationQueryService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService      queryService;
    private final NotificationPreferenceService preferenceService;

    @GetMapping
    @RequirePermission(resource = "NOTIFICATION", action = "READ")
    public ResponseEntity<ApiResponse<PageResult<NotificationDto>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean unreadOnly) {
        UUID userId = currentUserId();
        var result = queryService.listForUser(userId, unreadOnly, page, size);
        return ResponseEntity.ok(ApiResponse.ok(PageResult.from(result)));
    }

    /** Platform-admin listing across every tenant, regardless of recipient. */
    @GetMapping("/admin")
    public ResponseEntity<ApiResponse<PageResult<NotificationDto>>> listForAdmin(
            @RequestParam(required = false) UUID tenantId,
            @RequestParam(required = false) String channel,
            @RequestParam(required = false) Boolean read,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requirePlatformAdmin();
        var result = queryService.listForAdmin(tenantId, channel, read, page, size);
        return ResponseEntity.ok(ApiResponse.ok(PageResult.from(result)));
    }

    @GetMapping("/unread-count")
    @RequirePermission(resource = "NOTIFICATION", action = "READ")
    public ResponseEntity<ApiResponse<Map<String, Long>>> unreadCount() {
        UUID userId = currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(Map.of("count", queryService.countUnread(userId))));
    }

    @PostMapping("/{id}/read")
    @RequirePermission(resource = "NOTIFICATION", action = "UPDATE")
    public ResponseEntity<ApiResponse<NotificationDto>> markRead(@PathVariable UUID id) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(queryService.markRead(id, userId)));
    }

    @PostMapping("/read-all")
    @RequirePermission(resource = "NOTIFICATION", action = "UPDATE")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> markAllRead() {
        UUID userId = currentUserId();
        int updated = queryService.markAllRead(userId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("updated", updated)));
    }

    // ── Preferences ───────────────────────────────────────────────────────────

    /**
     * Effective preferences for the caller: platform defaults merged with their overrides, so
     * the client never has to know the defaults. TRANSACTIONAL is omitted — it is not a toggle.
     */
    @GetMapping("/preferences")
    @RequirePermission(resource = "NOTIFICATION", action = "READ")
    public ResponseEntity<ApiResponse<Map<String, List<NotificationPreferenceDto>>>> preferences() {
        UUID userId = requireUserId();
        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("preferences", toDtos(preferenceService.effectiveForUser(userId)))));
    }

    /** Partial update — categories absent from the body keep their current setting. */
    @PutMapping("/preferences")
    @RequirePermission(resource = "NOTIFICATION", action = "UPDATE")
    public ResponseEntity<ApiResponse<Map<String, List<NotificationPreferenceDto>>>> updatePreferences(
            @RequestBody @Valid UpdateNotificationPreferencesRequest req) {
        UUID userId = requireUserId();

        Map<NotificationCategory, NotificationPreferenceService.Setting> changes = new LinkedHashMap<>();
        for (UpdateNotificationPreferencesRequest.Entry e : req.getPreferences()) {
            NotificationCategory category = NotificationCategory.parse(e.getCategory())
                    .orElseThrow(() -> new PlatformException("UNKNOWN_CATEGORY",
                            "Unknown notification category: " + e.getCategory(), HttpStatus.BAD_REQUEST));
            changes.put(category,
                    new NotificationPreferenceService.Setting(e.getInApp(), e.getEmail()));
        }

        return ResponseEntity.ok(ApiResponse.ok(
                Map.of("preferences", toDtos(preferenceService.update(userId, changes)))));
    }

    private static List<NotificationPreferenceDto> toDtos(
            Map<NotificationCategory, NotificationPreferenceService.Setting> settings) {
        return settings.entrySet().stream()
                .map(e -> new NotificationPreferenceDto(e.getKey(), e.getValue().inApp(), e.getValue().email()))
                .toList();
    }

    private UUID currentUserId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getUserId() : null;
    }

    /** Preferences are strictly self-scoped — there is no path to read or write another user's. */
    private UUID requireUserId() {
        UUID userId = currentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return userId;
    }

    private static void requirePlatformAdmin() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        if (!ctx.isPlatformAdmin()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Platform admin role required");
        }
    }
}
