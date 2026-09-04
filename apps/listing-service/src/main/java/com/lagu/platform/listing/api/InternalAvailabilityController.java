package com.lagu.platform.listing.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.listing.service.ListingSnapshotService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.format.annotation.DateTimeFormat;

import java.time.LocalDate;
import java.util.UUID;

/**
 * Atomically claims/releases a single day of a listing's availability on behalf of another
 * platform service (booking-service). Deliberately separate from {@link ListingController}'s
 * owner-org-gated {@code PUT /availability}: that endpoint always writes the CALLER's own org,
 * which is wrong here — booking-service must claim a slot on the vendor's behalf using its own
 * internal-service identity, not by impersonating the vendor's org.
 */
@RestController
@RequestMapping("/internal/listings")
@RequiredArgsConstructor
public class InternalAvailabilityController {

    private final ListingSnapshotService snapshotService;

    @PostMapping("/{recordId}/availability/{date}/book")
    public ResponseEntity<ApiResponse<ClaimResult>> book(
            @PathVariable UUID recordId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody ClaimRequest req) {
        requireInternalCaller();
        boolean claimed = snapshotService.bookSlot(recordId, date, req.bookingRef());
        return ResponseEntity.ok(ApiResponse.ok(new ClaimResult(claimed)));
    }

    @PostMapping("/{recordId}/availability/{date}/release")
    public ResponseEntity<ApiResponse<ReleaseResult>> release(
            @PathVariable UUID recordId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate date,
            @RequestBody ClaimRequest req) {
        requireInternalCaller();
        boolean released = snapshotService.releaseSlot(recordId, date, req.bookingRef());
        return ResponseEntity.ok(ApiResponse.ok(new ReleaseResult(released)));
    }

    /**
     * Records a listing's review aggregate on behalf of booking-service, which owns the reviews.
     *
     * <p>Internal-only for the same reason the availability claims above are: this writes a
     * vendor's own snapshot, and booking-service must do it under its own service identity rather
     * than by impersonating the vendor's org. It is also the reason a consumer cannot post their
     * own rating straight onto a listing.
     */
    @PostMapping("/{recordId}/rating")
    public ResponseEntity<ApiResponse<RatingResult>> applyRating(
            @PathVariable UUID recordId, @RequestBody RatingRequest req) {
        requireInternalCaller();
        boolean applied = snapshotService.applyRating(recordId, req.average(), req.reviewCount());
        return ResponseEntity.ok(ApiResponse.ok(new RatingResult(applied)));
    }

    private void requireInternalCaller() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isInternalService()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal callers only");
        }
    }

    /** `average` is null when nothing verified has been said — not zero. See ListingSnapshot. */
    record RatingRequest(java.math.BigDecimal average, int reviewCount) {}
    record RatingResult(boolean applied) {}

    record ClaimRequest(UUID bookingRef) {}
    record ClaimResult(boolean claimed) {}
    record ReleaseResult(boolean released) {}
}
