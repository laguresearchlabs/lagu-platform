package com.lagu.platform.vendor.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.vendor.dto.*;
import com.lagu.platform.vendor.service.VendorService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vendors")
@RequiredArgsConstructor
public class VendorController {

    private final VendorService vendorService;

    /**
     * Registers a new vendor org. A user may register/belong to more than one — vendor orgs
     * are never written back to the caller's IAM platformOrgId, see VendorService.register.
     */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> register(@Valid @RequestBody RegisterVendorRequest req) {
        PlatformSecurityContext ctx = requireContext();
        VendorProfileResponse profile = vendorService.register(req, ctx.getUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(profile));
    }

    /** All vendor orgs the caller is a member of (owner or invited). */
    @GetMapping("/mine")
    public ResponseEntity<ApiResponse<List<VendorProfileResponse>>> listMine() {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.listMine(ctx.getUserId())));
    }

    /** Any member of the vendor org may view it; config/platform admins may view any. */
    @GetMapping("/{orgId}")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> getByOrgId(@PathVariable UUID orgId) {
        PlatformSecurityContext ctx = requireContext();
        VendorProfileResponse profile = ctx.isConfigAdmin()
                ? vendorService.getByOrgIdAsAdmin(orgId)
                : vendorService.getByOrgId(orgId, ctx.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    /** Vendor org's OWNER/ADMIN submits the profile for admin review. */
    @PostMapping("/{orgId}/submit")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> submit(@PathVariable UUID orgId) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.submit(orgId, ctx.getUserId())));
    }

    /** Recompute KYC readiness for a vendor org the caller is a member of. */
    @GetMapping("/{orgId}/kyc")
    public ResponseEntity<ApiResponse<KycChecklistDto>> kycStatus(@PathVariable UUID orgId) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.computeKyc(orgId, ctx.getUserId())));
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<List<VendorProfileResponse>>> list(
            @RequestParam(defaultValue = "SUBMITTED") String status) {
        requireAdmin();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.listByStatus(status)));
    }

    /** Admin changes vendor status (approve/suspend/reject). */
    @PatchMapping("/{orgId}/status")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> updateStatus(
            @PathVariable UUID orgId,
            @RequestBody StatusRequest req) {
        PlatformSecurityContext ctx = requireAdmin();
        return ResponseEntity.ok(ApiResponse.ok(
                vendorService.updateStatus(orgId, req.status(), ctx.getUserId())));
    }

    static PlatformSecurityContext requireContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new com.lagu.platform.common.exception.ValidationException("Authentication required");
        }
        return ctx;
    }

    /** These are cross-org review/admin operations — restricted to config/platform admins. */
    private PlatformSecurityContext requireAdmin() {
        PlatformSecurityContext ctx = requireContext();
        if (!ctx.isConfigAdmin()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.FORBIDDEN, "Admin role required");
        }
        return ctx;
    }

    record StatusRequest(String status) {}
}
