package com.lagu.platform.vendor.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
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
     * Registers a new vendor org. A user may register/belong to more than one — see
     * VendorService.register for how tenancy is tracked via VendorMember.
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
    @GetMapping("/{tenantId}")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> getByTenantId(@PathVariable UUID tenantId) {
        PlatformSecurityContext ctx = requireContext();
        VendorProfileResponse profile = ctx.isConfigAdmin()
                ? vendorService.getByTenantIdAsAdmin(tenantId)
                : vendorService.getByTenantId(tenantId, ctx.getUserId());
        return ResponseEntity.ok(ApiResponse.ok(profile));
    }

    /** Vendor org's OWNER/ADMIN submits the profile for admin review. */
    @PostMapping("/{tenantId}/submit")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> submit(@PathVariable UUID tenantId) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.submit(tenantId, ctx.getUserId())));
    }

    /** Recompute KYC readiness for a vendor org the caller is a member of. */
    @GetMapping("/{tenantId}/kyc")
    public ResponseEntity<ApiResponse<KycChecklistDto>> kycStatus(@PathVariable UUID tenantId) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.computeKyc(tenantId, ctx.getUserId())));
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    @GetMapping
    public ResponseEntity<ApiResponse<PageResult<VendorProfileResponse>>> list(
            @RequestParam(required = false) String status,
            @RequestParam(required = false) String search,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        requireAdmin();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.listForAdmin(status, search, page, size)));
    }

    /** Admin changes vendor status (approve/suspend/reject). */
    @PatchMapping("/{tenantId}/status")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> updateStatus(
            @PathVariable UUID tenantId,
            @RequestBody StatusRequest req) {
        PlatformSecurityContext ctx = requireAdmin();
        return ResponseEntity.ok(ApiResponse.ok(
                vendorService.updateStatus(tenantId, req.status(), ctx.getUserId())));
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
