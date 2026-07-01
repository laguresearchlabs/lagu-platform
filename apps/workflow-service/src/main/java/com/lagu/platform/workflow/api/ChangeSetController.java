package com.lagu.platform.workflow.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.workflow.domain.ChangeSet;
import com.lagu.platform.workflow.service.ChangeSetService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/change-sets")
@RequiredArgsConstructor
public class ChangeSetController {

    private final ChangeSetService changeSetService;

    /** Vendor submits a proposed change. */
    @PostMapping
    public ResponseEntity<ApiResponse<ChangeSet>> submit(@RequestBody SubmitRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        ChangeSet cs = changeSetService.submit(
                req.recordId(), req.orgId(), req.objectType(), req.workflowId(),
                req.originalData(), req.proposedData(),
                ctx != null ? ctx.getUserId() : req.submittedBy());
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(cs));
    }

    /** Admin approves or rejects a change set. */
    @PostMapping("/{id}/review")
    public ResponseEntity<ApiResponse<ChangeSet>> review(
            @PathVariable UUID id, @RequestBody ReviewRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        ChangeSet cs = changeSetService.review(id, req.decision(), req.adminComment(),
                req.correctedData(), ctx != null ? ctx.getUserId() : req.reviewedBy());
        return ResponseEntity.ok(ApiResponse.ok(cs));
    }

    /** Vendor withdraws a pending change set. */
    @PostMapping("/{id}/withdraw")
    public ResponseEntity<ApiResponse<ChangeSet>> withdraw(@PathVariable UUID id) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID userId = ctx != null ? ctx.getUserId() : null;
        return ResponseEntity.ok(ApiResponse.ok(changeSetService.withdraw(id, userId)));
    }

    @GetMapping("/record/{recordId}")
    public ResponseEntity<ApiResponse<List<ChangeSet>>> byRecord(@PathVariable UUID recordId) {
        return ResponseEntity.ok(ApiResponse.ok(changeSetService.listByRecord(recordId)));
    }

    /** Admin: all pending change sets (cross-org). */
    @GetMapping("/pending")
    public ResponseEntity<ApiResponse<List<ChangeSet>>> pending() {
        return ResponseEntity.ok(ApiResponse.ok(changeSetService.listPending()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<ChangeSet>>> byOrgAndStatus(
            @RequestParam(required = false, defaultValue = "PENDING") String status) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        if (orgId == null) return ResponseEntity.badRequest().build();
        return ResponseEntity.ok(ApiResponse.ok(changeSetService.listByOrgAndStatus(orgId, status)));
    }

    record SubmitRequest(UUID recordId, UUID orgId, String objectType, UUID workflowId,
                         Map<String, Object> originalData, Map<String, Object> proposedData,
                         UUID submittedBy) {}

    record ReviewRequest(String decision, String adminComment,
                         Map<String, Object> correctedData, UUID reviewedBy) {}
}
