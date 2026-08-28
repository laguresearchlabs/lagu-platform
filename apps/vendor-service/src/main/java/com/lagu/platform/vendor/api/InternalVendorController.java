package com.lagu.platform.vendor.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.vendor.domain.VendorProfileRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.Map;
import java.util.UUID;

/**
 * A vendor org's review status, for services that need to gate on it.
 *
 * listing-service is the caller: it refuses to publish a consumer-facing snapshot for an org that
 * has not reached ACTIVE. The public {@code GET /api/v1/vendors/{tenantId}} cannot serve this —
 * it is admin-or-own-org, and an internal service caller is neither.
 *
 * Deliberately narrow. It answers one question and returns one field, rather than exposing the
 * whole profile to any service that happens to hold the gateway secret.
 */
@RestController
@RequestMapping("/internal/vendors")
@RequiredArgsConstructor
public class InternalVendorController {

    private final VendorProfileRepository profileRepo;

    @GetMapping("/{tenantId}/status")
    public ResponseEntity<ApiResponse<Map<String, String>>> getStatus(@PathVariable UUID tenantId) {
        requireInternalCaller();
        return profileRepo.findById(tenantId)
                .map(p -> ResponseEntity.ok(ApiResponse.ok(Map.of("status", p.getStatus()))))
                .orElseGet(() -> ResponseEntity.notFound().build());
    }

    private void requireInternalCaller() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isInternalService()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal callers only");
        }
    }
}
