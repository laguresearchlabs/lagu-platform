package com.lagu.platform.booking.api;

import com.lagu.platform.booking.dto.BookingResponse;
import com.lagu.platform.booking.dto.CancelBookingRequest;
import com.lagu.platform.booking.dto.CreateBookingRequest;
import com.lagu.platform.booking.dto.QuoteBookingRequest;
import com.lagu.platform.booking.service.BookingService;
import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/bookings")
@RequiredArgsConstructor
public class BookingController {

    private final BookingService bookingService;

    @PostMapping
    public ResponseEntity<ApiResponse<BookingResponse>> create(@Valid @RequestBody CreateBookingRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(bookingService.create(req, requireUserId())));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<BookingResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.get(id, currentUserId(), currentTenantId())));
    }

    /** The caller's own bookings as a consumer, optionally scoped to one event. */
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<BookingResponse>>> listMine(
            @RequestParam(required = false) UUID eventId) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.listMine(requireUserId(), eventId)));
    }

    /** Bookings against the caller's vendor org (X-Tenant-Id resolved by the gateway). */
    @GetMapping("/vendor")
    public ResponseEntity<ApiResponse<List<BookingResponse>>> listVendor() {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.listVendor(currentTenantId())));
    }

    @PostMapping("/{id}/quote")
    public ResponseEntity<ApiResponse<BookingResponse>> quote(@PathVariable UUID id,
                                                               @Valid @RequestBody QuoteBookingRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.quote(id, req, currentUserId(), currentTenantId())));
    }

    @PostMapping("/{id}/confirm")
    public ResponseEntity<ApiResponse<BookingResponse>> confirm(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.confirm(id, currentUserId(), currentTenantId())));
    }

    @PostMapping("/{id}/complete")
    public ResponseEntity<ApiResponse<BookingResponse>> complete(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(bookingService.complete(id, currentUserId(), currentTenantId())));
    }

    @PostMapping("/{id}/cancel")
    public ResponseEntity<ApiResponse<BookingResponse>> cancel(@PathVariable UUID id,
                                                                @RequestBody(required = false) CancelBookingRequest req) {
        CancelBookingRequest body = req != null ? req : new CancelBookingRequest(null);
        return ResponseEntity.ok(ApiResponse.ok(bookingService.cancel(id, body, currentUserId(), currentTenantId())));
    }

    // ── caller-identity helpers ──────────────────────────────────────────────

    private static UUID requireUserId() {
        UUID userId = currentUserId();
        if (userId == null) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Authentication required");
        }
        return userId;
    }

    private static UUID currentUserId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getUserId() : null;
    }

    /** Nullable — most endpoints accept either a consumer or a vendor-org caller. */
    private static UUID currentTenantId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getTenantId() : null;
    }
}
