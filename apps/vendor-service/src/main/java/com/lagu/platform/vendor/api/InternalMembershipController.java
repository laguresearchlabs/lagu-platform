package com.lagu.platform.vendor.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.vendor.domain.VendorMemberRepository;
import com.lagu.platform.vendor.dto.MembershipRoleResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Lets other platform services (specifically gateway-service, resolving a caller's role for the
 * vendor org a request targets) look up a user's {@link com.lagu.platform.vendor.domain.VendorMember}
 * role. Internal-service callers only — answers "what role does this arbitrary userId have"
 * without a caller-is-a-member check.
 */
@RestController
@RequestMapping("/internal/memberships")
@RequiredArgsConstructor
public class InternalMembershipController {

    private final VendorMemberRepository memberRepo;

    @GetMapping("/{tenantId}/{userId}")
    public ResponseEntity<ApiResponse<MembershipRoleResponse>> getRole(@PathVariable UUID tenantId,
                                                                        @PathVariable UUID userId) {
        requireInternalCaller();
        return memberRepo.findByTenantIdAndUserIdAndStatus(tenantId, userId, "ACTIVE")
                .map(m -> ResponseEntity.ok(ApiResponse.ok(new MembershipRoleResponse(m.getRole()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void requireInternalCaller() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isInternalService()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal callers only");
        }
    }
}
