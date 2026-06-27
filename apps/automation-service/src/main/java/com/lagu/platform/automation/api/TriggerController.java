package com.lagu.platform.automation.api;

import com.lagu.platform.automation.domain.ActionDefinition;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.service.TriggerDefinitionService;
import com.lagu.platform.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/triggers")
@RequiredArgsConstructor
@Tag(name = "Triggers", description = "Automation trigger CRUD")
public class TriggerController {

    private final TriggerDefinitionService service;

    @GetMapping
    @RequirePermission(resource = "TRIGGER", action = "READ")
    @Operation(summary = "List trigger definitions for the org")
    public Page<TriggerDefinition> list(Pageable pageable) {
        return service.listForOrg(pageable);
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "TRIGGER", action = "READ")
    public TriggerDefinition get(@PathVariable UUID id) {
        return service.getById(id);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(resource = "TRIGGER", action = "CREATE")
    public TriggerDefinition create(@RequestBody Map<String, Object> req) {
        return service.create(req);
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public TriggerDefinition update(@PathVariable UUID id, @RequestBody Map<String, Object> req) {
        return service.update(id, req);
    }

    @DeleteMapping("/{id}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(resource = "TRIGGER", action = "DELETE")
    public void disable(@PathVariable UUID id) {
        service.disable(id);
    }

    // ── actions ───────────────────────────────────────────────────────────────

    @PostMapping("/{id}/actions")
    @ResponseStatus(HttpStatus.CREATED)
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public ActionDefinition addAction(@PathVariable UUID id, @RequestBody Map<String, Object> req) {
        return service.addAction(id, req);
    }

    @PutMapping("/{id}/actions/{actionId}")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public ActionDefinition updateAction(
            @PathVariable UUID id,
            @PathVariable UUID actionId,
            @RequestBody Map<String, Object> req) {
        return service.updateAction(id, actionId, req);
    }

    @DeleteMapping("/{id}/actions/{actionId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public void removeAction(@PathVariable UUID id, @PathVariable UUID actionId) {
        service.removeAction(id, actionId);
    }

    @PostMapping("/{id}/test")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    @Operation(summary = "Dry-run a trigger against sample record data — no side effects")
    public Map<String, String> dryRun(@PathVariable UUID id, @RequestBody Map<String, Object> sampleData) {
        service.dryRun(id, sampleData);
        return Map.of("status", "DRY_RUN_COMPLETE");
    }
}
