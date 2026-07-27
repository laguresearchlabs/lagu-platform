package com.lagu.platform.listing.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.listing.domain.ListingAvailability;
import com.lagu.platform.listing.domain.ListingSnapshot;
import com.lagu.platform.listing.service.ListingSnapshotService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/listings")
@RequiredArgsConstructor
public class ListingController {

    private final ListingSnapshotService snapshotService;

    // ── Consumer (public) endpoints ───────────────────────────────────────────

    /** Search published listings by vendor type (consumer-facing). */
    @GetMapping("/search")
    public ResponseEntity<ApiResponse<List<ListingSnapshot>>> search(
            @RequestParam String objectType,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        return ResponseEntity.ok(ApiResponse.ok(snapshotService.searchPublished(objectType, page, size)));
    }

    /**
     * A PUBLISHED snapshot is genuinely public (that's what "consumer-facing" means for this
     * endpoint) — anyone may view it. Anything else (UNPUBLISHED/SUSPENDED/a suspended vendor's
     * data, etc) previously had no gate at all: any authenticated user of any tenant could read
     * it by recordId. Now only the owning org (or a platform admin) can see a non-published one.
     */
    @GetMapping("/{recordId}/snapshot")
    public ResponseEntity<ApiResponse<ListingSnapshot>> getSnapshot(@PathVariable UUID recordId) {
        return snapshotService.getByRecordId(recordId)
                .filter(this::isVisibleToCaller)
                .map(s -> ResponseEntity.ok(ApiResponse.ok(s)))
                .orElse(ResponseEntity.notFound().build());
    }

    private boolean isVisibleToCaller(ListingSnapshot snapshot) {
        if ("PUBLISHED".equals(snapshot.getStatus())) return true;
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        return ctx != null && (ctx.isPlatformAdmin() || snapshot.getTenantId().equals(ctx.getTenantId()));
    }

    // ── Vendor (authenticated) endpoints ─────────────────────────────────────

    /** Vendor views all their own listing snapshots. */
    @GetMapping("/my")
    public ResponseEntity<ApiResponse<List<ListingSnapshot>>> myListings() {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(snapshotService.getByOrg(ctx.getTenantId())));
    }

    // ── Availability endpoints ────────────────────────────────────────────────

    @GetMapping("/{recordId}/availability")
    public ResponseEntity<ApiResponse<List<ListingAvailability>>> getAvailability(
            @PathVariable UUID recordId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        // Same visibility rule as getSnapshot: availability for a non-published listing (a
        // suspended vendor's, say) is only the owning org's or an admin's business.
        boolean visible = snapshotService.getByRecordId(recordId)
                .map(this::isVisibleToCaller)
                .orElse(false);
        if (!visible) return ResponseEntity.notFound().build();
        return ResponseEntity.ok(ApiResponse.ok(snapshotService.getAvailability(recordId, from, to)));
    }

    @PutMapping("/{recordId}/availability")
    public ResponseEntity<ApiResponse<List<ListingAvailability>>> setAvailability(
            @PathVariable UUID recordId,
            @RequestBody AvailabilityRequest req) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(
                snapshotService.setAvailability(recordId, ctx.getTenantId(),
                        req.from(), req.to(), req.slotType())));
    }

    // ── Admin: manually (re)publish a snapshot ────────────────────────────────

    @PostMapping("/{recordId}/publish")
    public ResponseEntity<ApiResponse<ListingSnapshot>> manualPublish(
            @PathVariable UUID recordId, @RequestBody PublishRequest req) {
        requireAdmin();
        // searchBoost is intentionally not accepted from the request — publishSnapshot derives
        // it from verificationTier so a caller can't set an arbitrary search-ranking boost.
        ListingSnapshot snap = snapshotService.publishSnapshot(
                recordId, req.tenantId(), req.objectType(), req.data(), req.verificationTier());
        return snap != null
                ? ResponseEntity.ok(ApiResponse.ok(snap))
                : ResponseEntity.badRequest().build();
    }

    @PostMapping("/{recordId}/unpublish")
    public ResponseEntity<Void> unpublish(@PathVariable UUID recordId) {
        requireAdmin();
        snapshotService.unpublishSnapshot(recordId);
        return ResponseEntity.noContent().build();
    }

    private PlatformSecurityContext requireContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new com.lagu.platform.common.exception.ValidationException("Authentication required");
        }
        return ctx;
    }

    /** Manual publish/unpublish bypass the normal workflow-transition path — admin only. */
    private PlatformSecurityContext requireAdmin() {
        PlatformSecurityContext ctx = requireContext();
        if (!ctx.isConfigAdmin()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Admin role required");
        }
        return ctx;
    }

    record AvailabilityRequest(
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            String slotType) {}

    record PublishRequest(java.util.UUID tenantId, String objectType,
                          java.util.Map<String, Object> data, String verificationTier) {}
}
