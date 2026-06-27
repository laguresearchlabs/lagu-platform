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
        Set<String> roles = ctx != null ? ctx.getRoles() : Set.of();
        return ResponseEntity.ok(ApiResponse.ok(engine.getPendingForUser(roles, olderThanMinutes)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ApprovalInstanceResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(engine.getById(id)));
    }

    @PostMapping("/{id}/decide")
    public ResponseEntity<ApiResponse<ApprovalInstanceResponse>> decide(
            @PathVariable UUID id, @Valid @RequestBody ApprovalDecisionRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID actorId = ctx != null ? ctx.getUserId() : null;
        return ResponseEntity.ok(ApiResponse.ok(engine.decide(id, req, actorId)));
    }
}
