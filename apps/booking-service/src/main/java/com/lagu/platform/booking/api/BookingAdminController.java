package com.lagu.platform.booking.api;

import com.lagu.platform.booking.domain.SettlementStatus;
import com.lagu.platform.booking.dto.BookingResponse;
import com.lagu.platform.booking.dto.SettlementActionRequest;
import com.lagu.platform.booking.dto.SettlementSummary;
import com.lagu.platform.booking.service.SettlementService;
import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * The commercial view of the marketplace, for platform admins.
 *
 * <p>Before this there was no way to see a single transaction: the admin portal had screens for
 * vendors, users, events, listings, schema, workflow and audit, and none for bookings, GMV or
 * commission. The company could not tell a working marketplace from a dead one.
 *
 * <p>Separate from {@link BookingController} because the authorization model is different in kind:
 * that one scopes every call to a party on the booking, while everything here is deliberately
 * cross-org and gated on platform admin instead.
 */
@RestController
@RequestMapping("/api/v1/bookings/admin")
@RequiredArgsConstructor
public class BookingAdminController {

    private final SettlementService settlementService;

    /** Every booking, optionally filtered to one settlement state — the admin's work queue. */
    @GetMapping
    public ResponseEntity<ApiResponse<List<BookingResponse>>> list(
            @RequestParam(required = false) SettlementStatus settlementStatus) {
        requirePlatformAdmin();
        return ResponseEntity.ok(ApiResponse.ok(settlementService.list(settlementStatus)));
    }

    /** GMV, commission earned, collected, outstanding and waived. */
    @GetMapping("/summary")
    public ResponseEntity<ApiResponse<SettlementSummary>> summary() {
        requirePlatformAdmin();
        return ResponseEntity.ok(ApiResponse.ok(settlementService.summary()));
    }

    @PostMapping("/{id}/settlement/invoice")
    public ResponseEntity<ApiResponse<BookingResponse>> invoice(
            @PathVariable UUID id, @RequestBody(required = false) SettlementActionRequest req) {
        requirePlatformAdmin();
        SettlementActionRequest body = req != null ? req : new SettlementActionRequest(null, null);
        return ResponseEntity.ok(ApiResponse.ok(
                settlementService.invoice(id, body.invoiceNumber(), body.note())));
    }

    @PostMapping("/{id}/settlement/paid")
    public ResponseEntity<ApiResponse<BookingResponse>> markPaid(
            @PathVariable UUID id, @RequestBody(required = false) SettlementActionRequest req) {
        requirePlatformAdmin();
        return ResponseEntity.ok(ApiResponse.ok(
                settlementService.markPaid(id, req != null ? req.note() : null)));
    }

    @PostMapping("/{id}/settlement/waive")
    public ResponseEntity<ApiResponse<BookingResponse>> waive(
            @PathVariable UUID id, @RequestBody(required = false) SettlementActionRequest req) {
        requirePlatformAdmin();
        return ResponseEntity.ok(ApiResponse.ok(
                settlementService.waive(id, req != null ? req.note() : null)));
    }

    /**
     * Platform admin only, not CONFIG_ADMIN. Configuring listing types is a different job from
     * seeing every vendor's revenue, and the two should not share a key.
     */
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
