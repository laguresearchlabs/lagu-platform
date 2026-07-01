package com.lagu.platform.vendor.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.vendor.dto.*;
import com.lagu.platform.vendor.service.VendorService;
import jakarta.servlet.http.HttpServletRequest;
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

    /** Vendor self-registration — creates org, VENDOR record, and binds user to org. */
    @PostMapping("/register")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> register(
            @Valid @RequestBody RegisterVendorRequest req,
            HttpServletRequest httpRequest) {
        PlatformSecurityContext ctx = requireContext();
        String bearer = httpRequest.getHeader("Authorization");
        VendorProfileResponse profile = vendorService.register(req, ctx.getUserId(), bearer);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(profile));
    }

    /** Authenticated vendor views their own profile. */
    @GetMapping("/me")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> myProfile() {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.getMyProfile(ctx.getUserId())));
    }

    /** Vendor submits profile for admin review. */
    @PostMapping("/me/submit")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> submit() {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.submit(ctx.getOrgId())));
    }

    /** Recompute KYC readiness for the authenticated vendor's org. */
    @GetMapping("/me/kyc")
    public ResponseEntity<ApiResponse<KycChecklistDto>> kycStatus() {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(vendorService.computeKyc(ctx.getOrgId())));
    }

    // ── Admin endpoints ──────────────────────────────────────────────────────

    @GetMapping("/{orgId}")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> getByOrgId(@PathVariable UUID orgId) {
        return ResponseEntity.ok(ApiResponse.ok(vendorService.getByOrgId(orgId)));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<VendorProfileResponse>>> list(
            @RequestParam(defaultValue = "SUBMITTED") String status) {
        return ResponseEntity.ok(ApiResponse.ok(vendorService.listByStatus(status)));
    }

    /** Admin changes vendor status (approve/suspend/reject). */
    @PatchMapping("/{orgId}/status")
    public ResponseEntity<ApiResponse<VendorProfileResponse>> updateStatus(
            @PathVariable UUID orgId,
            @RequestBody StatusRequest req) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(
                vendorService.updateStatus(orgId, req.status(), ctx.getUserId())));
    }

    private PlatformSecurityContext requireContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || ctx.getUserId() == null) {
            throw new com.lagu.platform.common.exception.ValidationException("Authentication required");
        }
        return ctx;
    }

    record StatusRequest(String status) {}
}
