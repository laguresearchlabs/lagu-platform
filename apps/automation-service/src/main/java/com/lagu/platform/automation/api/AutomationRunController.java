package com.lagu.platform.automation.api;

import com.lagu.platform.automation.domain.AutomationRun;
import com.lagu.platform.automation.domain.AutomationRunRepository;
import com.lagu.platform.common.exception.ResourceNotFoundException;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.RequirePermission;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
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
    public Page<AutomationRun> list(Pageable pageable) {
        UUID orgId = GatewayHeaderFilter.current().getOrgId();
        return runRepository.findByOrgId(orgId, pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "TRIGGER", action = "READ")
    public AutomationRun get(@PathVariable UUID id) {
        UUID orgId = GatewayHeaderFilter.current().getOrgId();
        AutomationRun run = runRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("AutomationRun", id.toString()));
        if (!orgId.equals(run.getOrgId())) {
            throw new ResourceNotFoundException("AutomationRun", id.toString());
        }
        return run;
    }
}
