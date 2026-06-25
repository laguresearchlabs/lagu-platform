package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.dto.*;
import com.lagu.platform.metadata.service.ObjectTypeService;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/object-types")
@RequiredArgsConstructor
public class ObjectTypeController {

    private final ObjectTypeService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<ObjectTypeResponse>>> list() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        return ResponseEntity.ok(ApiResponse.ok(service.listForOrg(orgId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<ObjectTypeResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @GetMapping("/by-name/{name}/schema")
    public ResponseEntity<ApiResponse<ObjectTypeSchema>> getSchema(@PathVariable String name) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        ObjectTypeSchema schema = orgId != null
                ? service.getSchema(name, orgId)
                : service.getSchema(name);
        return ResponseEntity.ok(ApiResponse.ok(schema));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<ObjectTypeResponse>> create(@Valid @RequestBody ObjectTypeRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req, orgId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<ObjectTypeResponse>> update(@PathVariable UUID id,
                                                                   @Valid @RequestBody ObjectTypeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @PostMapping("/{id}/sections")
    public ResponseEntity<ApiResponse<ObjectTypeResponse>> addSection(@PathVariable UUID id,
                                                                       @Valid @RequestBody ObjectTypeSectionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.addSection(id, req)));
    }

    @DeleteMapping("/{id}/sections/{sectionId}")
    public ResponseEntity<Void> removeSection(@PathVariable UUID id, @PathVariable UUID sectionId) {
        service.removeSection(id, sectionId);
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
