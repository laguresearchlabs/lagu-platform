package com.lagu.platform.automation.api;

import com.lagu.platform.automation.domain.AutomationRun;
import com.lagu.platform.automation.domain.AutomationRunRepository;
import com.lagu.platform.automation.dto.AutomationRunResponse;
import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.RequirePermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.UUID;

@RestController
@RequestMapping("/api/v1/runs")
@RequiredArgsConstructor
@Tag(name = "Automation Runs", description = "Automation execution history")
public class AutomationRunController {

    private final AutomationRunRepository runRepository;

    @GetMapping
    @RequirePermission(resource = "TRIGGER", action = "READ")
    public ResponseEntity<ApiResponse<PageResult<AutomationRunResponse>>> list(Pageable pageable) {
        UUID tenantId = GatewayHeaderFilter.current().getTenantId();
        var page = runRepository.findByTenantId(tenantId, pageable).map(AutomationRunResponse::summary);
        return ResponseEntity.ok(ApiResponse.ok(PageResult.from(page)));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "TRIGGER", action = "READ")
    public ResponseEntity<ApiResponse<AutomationRunResponse>> get(@PathVariable UUID id) {
        UUID tenantId = GatewayHeaderFilter.current().getTenantId();
        AutomationRun run = runRepository.findByIdWithActionRuns(id)
                .orElseThrow(() -> new ResourceNotFoundException("AutomationRun", id.toString()));
        if (!tenantId.equals(run.getTenantId())) {
            throw new ResourceNotFoundException("AutomationRun", id.toString());
        }
        return ResponseEntity.ok(ApiResponse.ok(AutomationRunResponse.from(run)));
    }
}
