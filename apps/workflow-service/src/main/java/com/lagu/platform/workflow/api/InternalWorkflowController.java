package com.lagu.platform.workflow.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.workflow.service.ChangeSetService;
import com.lagu.platform.workflow.service.ChangeSetService.GatedStates;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import java.util.UUID;

/**
 * Which of an object type's workflow states hold edits for review, and the workflow they belong to.
 *
 * record-service is the caller. It caches the answer and checks it on every update, which is what
 * finally wires up {@code requiresChangeApproval}. The flag turned out to be unreachable from both
 * ends: nothing on the record write path ever read it, and {@code WorkflowStateRequest} never
 * carried it either, so it could only ever be false through the API. Both are fixed together —
 * either alone leaves the gate inert.
 *
 * <p>Shaped as "give me the whole gated set for this type" rather than "is this one state gated?"
 * so a single cached answer covers every record of that type. A per-record question would be a
 * network round trip on every vendor edit.
 */
@RestController
@RequestMapping("/internal/workflows")
@RequiredArgsConstructor
public class InternalWorkflowController {

    private final ChangeSetService changeSetService;

    @GetMapping("/gated-states")
    public ResponseEntity<ApiResponse<GatedStates>> gatedStates(
            @RequestParam String objectType,
            @RequestParam(required = false) UUID tenantId) {
        requireInternalCaller();
        return ResponseEntity.ok(ApiResponse.ok(changeSetService.gatedStatesFor(objectType, tenantId)));
    }

    private void requireInternalCaller() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx == null || !ctx.isInternalService()) {
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "Internal callers only");
        }
    }
}
