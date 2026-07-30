package com.lagu.platform.notification.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.notification.dto.NotificationDto;
import com.lagu.platform.notification.service.NotificationQueryService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService queryService;

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

    private UUID currentUserId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getUserId() : null;
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
