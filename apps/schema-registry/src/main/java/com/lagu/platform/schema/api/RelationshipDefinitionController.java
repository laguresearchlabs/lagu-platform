package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.dto.RelationshipDefinitionRequest;
import com.lagu.platform.schema.dto.RelationshipDefinitionResponse;
import com.lagu.platform.schema.service.RelationshipDefinitionService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/relationship-definitions")
@RequiredArgsConstructor
public class RelationshipDefinitionController {

    private final RelationshipDefinitionService service;

    @GetMapping
    @RequirePermission(resource = "RELATIONSHIP", action = "READ")
    public ResponseEntity<ApiResponse<List<RelationshipDefinitionResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(service.list()));
    }

    @GetMapping("/{id}")
    @RequirePermission(resource = "RELATIONSHIP", action = "READ")
    public ResponseEntity<ApiResponse<RelationshipDefinitionResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(service.get(id)));
    }

    @GetMapping("/by-name/{name}")
    public ResponseEntity<ApiResponse<RelationshipDefinitionResponse>> getByName(@PathVariable String name) {
        return ResponseEntity.ok(ApiResponse.ok(service.getByName(name)));
    }

    @PostMapping
    @RequirePermission(resource = "RELATIONSHIP", action = "CREATE")
    public ResponseEntity<ApiResponse<RelationshipDefinitionResponse>> create(
            @Valid @RequestBody RelationshipDefinitionRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(service.create(req)));
    }

    @PutMapping("/{id}")
    @RequirePermission(resource = "RELATIONSHIP", action = "UPDATE")
    public ResponseEntity<ApiResponse<RelationshipDefinitionResponse>> update(
            @PathVariable UUID id, @Valid @RequestBody RelationshipDefinitionRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(service.update(id, req)));
    }

    @DeleteMapping("/{id}")
    @RequirePermission(resource = "RELATIONSHIP", action = "DELETE")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        service.delete(id);
        return ResponseEntity.noContent().build();
    }
}
