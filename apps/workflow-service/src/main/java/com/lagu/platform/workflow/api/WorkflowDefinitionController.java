package com.lagu.platform.workflow.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.workflow.dto.*;
import com.lagu.platform.workflow.service.WorkflowDefinitionService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/workflow-definitions")
@RequiredArgsConstructor
public class WorkflowDefinitionController {

    private final WorkflowDefinitionService service;

    @GetMapping
    @RequirePermission(resource = "WORKFLOW", action = "READ")
    public ResponseEntity<ApiResponse<List<WorkflowDefinitionResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listAll()));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "WORKFLOW", action = "READ")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    @RequirePermission(resource = "WORKFLOW", action = "CREATE")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> create(
            @Valid @RequestBody WorkflowDefinitionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @PostMapping("/{id}/states")
    @RequirePermission(resource = "WORKFLOW", action = "UPDATE")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> addState(
            @PathVariable UUID id, @Valid @RequestBody WorkflowStateRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.addState(id, req)));
    }

    @PostMapping("/{id}/transitions")
    @RequirePermission(resource = "WORKFLOW", action = "UPDATE")
    public ResponseEntity<ApiResponse<WorkflowDefinitionResponse>> addTransition(
            @PathVariable UUID id, @Valid @RequestBody WorkflowTransitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.addTransition(id, req)));
    }
}
