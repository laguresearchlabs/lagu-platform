package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.dto.*;
import com.lagu.platform.metadata.service.RoleService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/roles")
@RequiredArgsConstructor
public class RoleController {

    private final RoleService service;

    @GetMapping
    @RequirePermission(resource = "ROLE", action = "READ")
    public ResponseEntity<ApiResponse<List<RoleResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.listRoles()));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "ROLE", action = "READ")
    public ResponseEntity<ApiResponse<RoleResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getRole(id)));
    }

    @PostMapping
    @RequirePermission(resource = "ROLE", action = "CREATE")
    public ResponseEntity<ApiResponse<RoleResponse>> create(@Valid @RequestBody RoleRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.createCustomRole(req)));
    }

    @GetMapping("/{id}/permissions")
    @RequirePermission(resource = "PERMISSION", action = "READ")
    public ResponseEntity<ApiResponse<List<PermissionResponse>>> listPermissions(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.listPermissions(id)));
    }

    @PostMapping("/{id}/permissions")
    @RequirePermission(resource = "PERMISSION", action = "CREATE")
    public ResponseEntity<ApiResponse<PermissionResponse>> grantPermission(
            @PathVariable UUID id, @Valid @RequestBody PermissionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.grantPermission(id, req)));
    }

    @DeleteMapping("/{id}/permissions/{permId}")
    @RequirePermission(resource = "PERMISSION", action = "DELETE")
    public ResponseEntity<Void> revokePermission(@PathVariable UUID id, @PathVariable UUID permId) {
        service.revokePermission(id, permId);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/{id}/users/{userId}")
    @RequirePermission(resource = "ROLE", action = "UPDATE")
    public ResponseEntity<Void> assignToUser(@PathVariable UUID id, @PathVariable UUID userId) {
        service.assignRoleToUser(id, userId);
        return ResponseEntity.status(HttpStatus.CREATED).build();
    }

    @DeleteMapping("/{id}/users/{userId}")
    @RequirePermission(resource = "ROLE", action = "UPDATE")
    public ResponseEntity<Void> revokeFromUser(@PathVariable UUID id, @PathVariable UUID userId) {
        service.revokeRoleFromUser(id, userId);
        return ResponseEntity.noContent().build();
    }
}
