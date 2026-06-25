package com.lagu.platform.workflow.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import com.lagu.platform.workflow.domain.TransitionHistory;
import com.lagu.platform.workflow.domain.TransitionHistoryRepository;
import com.lagu.platform.workflow.dto.RecordWorkflowStatusResponse;
import com.lagu.platform.workflow.service.StateMachineEngine;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
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
    public ResponseEntity<ApiResponse<RecordWorkflowStatusResponse>> getStatus(
            @PathVariable UUID recordId) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        Set<String> roles = ctx != null ? ctx.getRoles() : Set.of();
        return ResponseEntity.ok(ApiResponse.ok(engine.getStatus(recordId, roles)));
    }

    @GetMapping("/history")
    public ResponseEntity<ApiResponse<PageResult<TransitionHistory>>> getHistory(
            @PathVariable UUID recordId,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "20") int size) {
        var results = histRepo.findByRecordIdOrderByTransitionedAtDesc(
                recordId, PageRequest.of(page, size));
        return ResponseEntity.ok(ApiResponse.ok(PageResult.from(results)));
    }
}
