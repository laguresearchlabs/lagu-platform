package com.lagu.platform.workflow.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.workflow.dto.ApprovalDecisionRequest;
import com.lagu.platform.workflow.dto.ApprovalInstanceResponse;
import com.lagu.platform.workflow.service.ApprovalEngine;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/approvals")
@RequiredArgsConstructor
public class ApprovalController {

    private final ApprovalEngine engine;

    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<ApprovalInstanceResponse>>> pending(
            @RequestParam(required = false) Integer olderThanMinutes) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        // Internal callers (e.g. automation-service's approval-timeout escalation scheduler) have
        // no user/org of their own — they need the platform-wide view across every org, not one
        // caller's org+role-filtered slice. A human PLATFORM_ADMIN needs the same unfiltered view
        // for a cross-platform approvals dashboard.
        if (ctx != null && (ctx.isInternalService() || ctx.isPlatformAdmin())) {
            return ResponseEntity.ok(ApiResponse.ok(
                    engine.getAllTimedOut(olderThanMinutes != null ? olderThanMinutes : 0)));
        }
        ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(
                engine.getPendingForUser(ctx.getTenantId(), ctx.getRoles(), olderThanMinutes)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ApprovalInstanceResponse>> getById(@PathVariable UUID id) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(engine.getById(id, ctx.getTenantId(), ctx.isPlatformAdmin())));
    }

    @PostMapping("/{id}/decide")
    public ResponseEntity<ApiResponse<ApprovalInstanceResponse>> decide(
            @PathVariable UUID id, @Valid @RequestBody ApprovalDecisionRequest req) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(
                engine.decide(id, req, ctx.getUserId(), ctx.getTenantId(), ctx.getRoles())));
    }

    private PlatformSecurityContext requireContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !(ctx.isOrgMember() || ctx.isPlatformAdmin())) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Missing authenticated context");
        }
        return ctx;
    }
}
