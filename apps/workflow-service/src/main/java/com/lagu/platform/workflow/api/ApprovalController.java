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
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(
                engine.getPendingForUser(ctx.getOrgId(), ctx.getRoles(), olderThanMinutes)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ApprovalInstanceResponse>> getById(@PathVariable UUID id) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(engine.getById(id, ctx.getOrgId())));
    }

    @PostMapping("/{id}/decide")
    public ResponseEntity<ApiResponse<ApprovalInstanceResponse>> decide(
            @PathVariable UUID id, @Valid @RequestBody ApprovalDecisionRequest req) {
        PlatformSecurityContext ctx = requireContext();
        return ResponseEntity.ok(ApiResponse.ok(
                engine.decide(id, req, ctx.getUserId(), ctx.getOrgId(), ctx.getRoles())));
    }

    private PlatformSecurityContext requireContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isOrgMember()) {
            throw new org.springframework.web.server.ResponseStatusException(
                    org.springframework.http.HttpStatus.UNAUTHORIZED, "Missing authenticated context");
        }
        return ctx;
    }
}
