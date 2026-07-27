package com.lagu.platform.workflow.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.PlatformException;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.security.RequirePermission;
import com.lagu.platform.workflow.domain.TransitionHistory;
import com.lagu.platform.workflow.domain.TransitionHistoryRepository;
import com.lagu.platform.workflow.dto.RecordWorkflowStatusResponse;
import com.lagu.platform.workflow.service.StateMachineEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.Set;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/records/{recordId}/workflow")
@RequiredArgsConstructor
public class RecordWorkflowController {

    private final StateMachineEngine        engine;
    private final TransitionHistoryRepository histRepo;

    @GetMapping
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<RecordWorkflowStatusResponse>> getStatus(
            @PathVariable UUID recordId) {
        PlatformSecurityContext ctx = requireContext();
        Set<String> roles = ctx.getRoles();
        UUID tenantId = ctx.isPlatformAdmin() ? null : requireTenantId(ctx);
        return ResponseEntity.ok(ApiResponse.ok(engine.getStatus(recordId, tenantId, roles)));
    }

    @GetMapping("/history")
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<PageResult<TransitionHistory>>> getHistory(
            @PathVariable UUID recordId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        PlatformSecurityContext ctx = requireContext();
        var pageReq = PageRequest.of(page, size);
        var results = ctx.isPlatformAdmin()
                ? histRepo.findByRecordIdOrderByTransitionedAtDesc(recordId, pageReq)
                : histRepo.findByRecordIdAndTenantIdOrderByTransitionedAtDesc(recordId, requireTenantId(ctx), pageReq);
        return ResponseEntity.ok(ApiResponse.ok(PageResult.from(results)));
    }

    // Every other tenancy check in the platform (RecordService.findForContext,
    // RecordVerificationService.getByRecordId) fails closed the same way — no org context on a
    // non-admin caller is a 403, never a silent unscoped lookup. This endpoint previously had
    // neither check at all.
    private PlatformSecurityContext requireContext() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null) {
            throw new PlatformException("AUTH_REQUIRED", "Authentication required", HttpStatus.UNAUTHORIZED);
        }
        return ctx;
    }

    private UUID requireTenantId(PlatformSecurityContext ctx) {
        if (ctx.getTenantId() == null) {
            throw new PlatformException("TENANT_CONTEXT_REQUIRED",
                    "An organization context is required to access workflow state", HttpStatus.FORBIDDEN);
        }
        return ctx.getTenantId();
    }
}
