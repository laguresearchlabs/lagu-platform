package com.lagu.platform.vendor.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.RequirePermission;
import com.lagu.platform.vendor.dto.InviteVendorMemberRequest;
import com.lagu.platform.vendor.dto.UpdateVendorMemberRoleRequest;
import com.lagu.platform.vendor.dto.VendorMemberResponse;
import com.lagu.platform.vendor.service.VendorMemberService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/vendors/{tenantId}/members")
@RequiredArgsConstructor
public class VendorMemberController {

    private final VendorMemberService memberService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<VendorMemberResponse>>> list(@PathVariable UUID tenantId) {
        return ResponseEntity.ok(ApiResponse.ok(
                memberService.list(tenantId, VendorController.requireContext().getUserId())));
    }

    /** OWNER/ADMIN invites another user (who may already belong to other vendor orgs) as a member. */
    @RequirePermission(resource = "VENDOR_MEMBER", action = "CREATE")
    @PostMapping
    public ResponseEntity<ApiResponse<VendorMemberResponse>> invite(@PathVariable UUID tenantId,
                                                                      @Valid @RequestBody InviteVendorMemberRequest req) {
        VendorMemberResponse member = memberService.invite(
                tenantId, VendorController.requireContext().getUserId(), req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(member));
    }

    @RequirePermission(resource = "VENDOR_MEMBER", action = "UPDATE")
    @PatchMapping("/{targetUserId}/role")
    public ResponseEntity<ApiResponse<VendorMemberResponse>> updateRole(
            @PathVariable UUID tenantId, @PathVariable UUID targetUserId,
            @Valid @RequestBody UpdateVendorMemberRoleRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(memberService.updateRole(
                tenantId, VendorController.requireContext().getUserId(), targetUserId, req)));
    }

    @RequirePermission(resource = "VENDOR_MEMBER", action = "DELETE")
    @DeleteMapping("/{targetUserId}")
    public ResponseEntity<Void> remove(@PathVariable UUID tenantId, @PathVariable UUID targetUserId) {
        memberService.remove(tenantId, VendorController.requireContext().getUserId(), targetUserId);
        return ResponseEntity.noContent().build();
    }
}
