package com.lagu.platform.booking.api;

import com.lagu.platform.booking.dto.CreateReviewRequest;
import com.lagu.platform.booking.dto.ListingRatingResponse;
import com.lagu.platform.booking.dto.ReviewResponse;
import com.lagu.platform.booking.dto.VendorReplyRequest;
import com.lagu.platform.booking.service.ReviewService;
import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

import java.util.List;
import java.util.UUID;

/**
 * Reviews of a vendor's listings.
 *
 * <p>Separate from {@link BookingController} despite sharing a service and a schema: a review is
 * read by people who have not booked anything and never will, and folding it into the bookings
 * controller would make that shape harder to see, not easier.
 */
@RestController
@RequestMapping("/api/v1/reviews")
@RequiredArgsConstructor
@Tag(name = "Reviews", description = "Consumer reviews of vendor listings")
public class ReviewController {

    private final ReviewService reviewService;

    @PostMapping
    @Operation(summary = "Leave a review. Verified automatically if a completed booking backs it")
    public ResponseEntity<ApiResponse<ReviewResponse>> create(
            @Valid @RequestBody CreateReviewRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(reviewService.create(req, requireUserId())));
    }

    /**
     * A listing's reviews. Deliberately readable without a session — this is what a shopper
     * comparing three venues is reading, and most of them are not signed in yet.
     */
    @GetMapping("/listing/{listingRecordId}")
    @Operation(summary = "Reviews for one listing, newest first")
    public ResponseEntity<ApiResponse<List<ReviewResponse>>> listForListing(
            @PathVariable UUID listingRecordId) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.listForListing(listingRecordId)));
    }

    /** The aggregate behind a card's "4.8 (62)". Public for the same reason. */
    @GetMapping("/listing/{listingRecordId}/rating")
    @Operation(summary = "Average rating and verified review count for one listing")
    public ResponseEntity<ApiResponse<ListingRatingResponse>> rating(
            @PathVariable UUID listingRecordId) {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.rating(listingRecordId)));
    }

    @GetMapping("/vendor")
    @Operation(summary = "Everything said about the calling vendor org")
    public ResponseEntity<ApiResponse<List<ReviewResponse>>> listForVendor() {
        return ResponseEntity.ok(ApiResponse.ok(reviewService.listForVendor(currentTenantId())));
    }

    @PostMapping("/{id}/reply")
    @Operation(summary = "The vendor's right of reply. Only the vendor the review is about")
    public ResponseEntity<ApiResponse<ReviewResponse>> reply(
            @PathVariable UUID id, @Valid @RequestBody VendorReplyRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(
                reviewService.reply(id, req, requireUserId(), currentTenantId())));
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

    private static UUID currentTenantId() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null ? ctx.getTenantId() : null;
    }
}
