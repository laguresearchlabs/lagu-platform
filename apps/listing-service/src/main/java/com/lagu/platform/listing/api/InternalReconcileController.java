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
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * Releases snapshots held behind the KYC gate once a vendor org reaches ACTIVE.
 *
 * vendor-service calls this on the DRAFT/SUBMITTED/UNDER_REVIEW → ACTIVE transition. It is the
 * other half of the gate: without it, a listing approved before the org completed KYC would stay
 * invisible permanently, because nothing else in the platform re-publishes an approved listing.
 *
 * Idempotent, so it doubles as the manual recovery path when vendor-service's best-effort call
 * does not land.
 */
@RestController
@RequestMapping("/internal/listings")
@RequiredArgsConstructor
public class InternalReconcileController {

    private final ListingSnapshotService snapshotService;

    @PostMapping("/reconcile/{tenantId}")
    public ResponseEntity<ApiResponse<Map<String, Integer>>> reconcile(@PathVariable UUID tenantId) {
        requireInternalCaller();
        int released = snapshotService.reconcileHeldSnapshots(tenantId);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("released", released)));
    }

    private void requireInternalCaller() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isInternalService()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal callers only");
        }
    }
}
