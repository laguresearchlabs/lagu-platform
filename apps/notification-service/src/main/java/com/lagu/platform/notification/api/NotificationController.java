package com.lagu.platform.notification.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.notification.dto.NotificationDto;
import com.lagu.platform.notification.service.NotificationQueryService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/notifications")
@RequiredArgsConstructor
public class NotificationController {

    private final NotificationQueryService queryService;

    @GetMapping
    @RequirePermission(resource = "NOTIFICATION", action = "READ")
    public ResponseEntity<ApiResponse<Page<NotificationDto>>> list(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size,
            @RequestParam(required = false) Boolean unreadOnly) {
        UUID userId = currentUserId();
        return ResponseEntity.ok(ApiResponse.ok(queryService.listForUser(userId, unreadOnly, page, size)));
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
}
