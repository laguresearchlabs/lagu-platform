package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.dto.EntityAttributeRequest;
import com.lagu.platform.metadata.dto.EntityRequest;
import com.lagu.platform.metadata.dto.EntityResponse;
import com.lagu.platform.metadata.service.EntityService;
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
@RequestMapping("/api/v1/entities")
@RequiredArgsConstructor
public class EntityController {

    private final EntityService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<EntityResponse>>> list() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        return ResponseEntity.ok(ApiResponse.ok(service.listForOrg(orgId)));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<EntityResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<EntityResponse>> create(@Valid @RequestBody EntityRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req, orgId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<EntityResponse>> update(@PathVariable UUID id,
                                                               @Valid @RequestBody EntityRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @PostMapping("/{id}/attributes")
    public ResponseEntity<ApiResponse<EntityResponse>> addAttribute(@PathVariable UUID id,
                                                                     @Valid @RequestBody EntityAttributeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.addAttribute(id, req)));
    }

    @DeleteMapping("/{id}/attributes/{attributeId}")
    public ResponseEntity<ApiResponse<EntityResponse>> removeAttribute(@PathVariable UUID id,
                                                                        @PathVariable UUID attributeId) {
        return ResponseEntity.ok(ApiResponse.ok(service.removeAttribute(id, attributeId)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
