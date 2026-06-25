package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.dto.AttributeRequest;
import com.lagu.platform.metadata.dto.AttributeResponse;
import com.lagu.platform.metadata.service.AttributeService;
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
@RequestMapping("/api/v1/attributes")
@RequiredArgsConstructor
public class AttributeController {

    private final AttributeService service;

    @GetMapping
    public ResponseEntity<ApiResponse<List<AttributeResponse>>> list() {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        List<AttributeResponse> result = ctx != null && ctx.getOrgId() != null
                ? service.listForOrg(ctx.getOrgId())
                : service.listPlatformLevel();
        return ResponseEntity.ok(ApiResponse.ok(result));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<AttributeResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<AttributeResponse>> create(@Valid @RequestBody AttributeRequest req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(req, orgId)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<AttributeResponse>> update(@PathVariable UUID id,
                                                                  @Valid @RequestBody AttributeRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        service.deactivate(id);
        return ResponseEntity.noContent().build();
    }
}
