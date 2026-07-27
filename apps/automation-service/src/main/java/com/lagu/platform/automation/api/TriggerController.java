package com.lagu.platform.automation.api;

import com.lagu.platform.automation.domain.ActionDefinition;
import com.lagu.platform.automation.domain.TriggerDefinition;
import com.lagu.platform.automation.dto.ActionDefinitionResponse;
import com.lagu.platform.automation.dto.CreateActionRequest;
import com.lagu.platform.automation.dto.CreateTriggerRequest;
import com.lagu.platform.automation.dto.TriggerDefinitionResponse;
import com.lagu.platform.automation.service.TriggerDefinitionService;
import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.common.dto.PageResult;
import com.lagu.platform.security.RequirePermission;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
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
    public ResponseEntity<ApiResponse<PageResult<TriggerDefinitionResponse>>> list(Pageable pageable) {
        var page = service.listForOrg(pageable).map(TriggerDefinitionResponse::from);
        return ResponseEntity.ok(ApiResponse.ok(PageResult.from(page)));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "TRIGGER", action = "READ")
    public ResponseEntity<ApiResponse<TriggerDefinitionResponse>> get(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(TriggerDefinitionResponse.from(service.getById(id))));
    }

    @PostMapping
    @RequirePermission(resource = "TRIGGER", action = "CREATE")
    public ResponseEntity<ApiResponse<TriggerDefinitionResponse>> create(@Valid @RequestBody CreateTriggerRequest req) {
        TriggerDefinition created = service.create(req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(TriggerDefinitionResponse.from(created)));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public ResponseEntity<ApiResponse<TriggerDefinitionResponse>> update(
            @PathVariable UUID id, @RequestBody Map<String, Object> req) {
        return ResponseEntity.ok(ApiResponse.ok(TriggerDefinitionResponse.from(service.update(id, req))));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = "TRIGGER", action = "DELETE")
    public ResponseEntity<Void> disable(@PathVariable UUID id) {
        service.disable(id);
        return ResponseEntity.noContent().build();
    }

    // ── actions ───────────────────────────────────────────────────────────────

    @PostMapping("/{id}/actions")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public ResponseEntity<ApiResponse<ActionDefinitionResponse>> addAction(
            @PathVariable UUID id, @Valid @RequestBody CreateActionRequest req) {
        ActionDefinition action = service.addAction(id, req);
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(ActionDefinitionResponse.from(action)));
    }

    @PutMapping("/{id}/actions/{actionId}")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public ResponseEntity<ApiResponse<ActionDefinitionResponse>> updateAction(
            @PathVariable UUID id,
            @PathVariable UUID actionId,
            @RequestBody Map<String, Object> req) {
        return ResponseEntity.ok(ApiResponse.ok(ActionDefinitionResponse.from(service.updateAction(id, actionId, req))));
    }

    @DeleteMapping("/{id}/actions/{actionId}")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    public ResponseEntity<Void> removeAction(@PathVariable UUID id, @PathVariable UUID actionId) {
        service.removeAction(id, actionId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/test")
    @RequirePermission(resource = "TRIGGER", action = "UPDATE")
    @Operation(summary = "Dry-run a trigger against sample record data — no side effects")
    public ResponseEntity<ApiResponse<Map<String, String>>> dryRun(
            @PathVariable UUID id, @RequestBody Map<String, Object> sampleData) {
        service.dryRun(id, sampleData);
        return ResponseEntity.ok(ApiResponse.ok(Map.of("status", "DRY_RUN_COMPLETE")));
    }
}
