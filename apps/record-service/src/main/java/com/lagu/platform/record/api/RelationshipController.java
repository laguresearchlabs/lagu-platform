package com.lagu.platform.record.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.record.dto.CreateRelationshipRequest;
import com.lagu.platform.record.dto.RelationshipResponse;
import com.lagu.platform.record.service.RelationshipService;
import com.lagu.platform.security.RequirePermission;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/records/{sourceId}/relationships")
@RequiredArgsConstructor
public class RelationshipController {

    private final RelationshipService service;

    @GetMapping
    @RequirePermission(resource = "RECORD", action = "READ")
    public ResponseEntity<ApiResponse<List<RelationshipResponse>>> list(
            @PathVariable UUID sourceId,
            @RequestParam(required = false) String relationshipName) {
        return ResponseEntity.ok(ApiResponse.ok(service.list(sourceId, relationshipName)));
    }

    @PostMapping
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<ApiResponse<RelationshipResponse>> create(
            @PathVariable UUID sourceId,
            @Valid @RequestBody CreateRelationshipRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(service.create(sourceId, req)));
    }

    @DeleteMapping("/{relationshipName}/{targetId}")
    @RequirePermission(resource = "RECORD", action = "UPDATE")
    public ResponseEntity<Void> delete(
            @PathVariable UUID sourceId,
            @PathVariable String relationshipName,
            @PathVariable UUID targetId) {
        service.delete(sourceId, relationshipName, targetId);
        return ResponseEntity.noContent().build();
    }
}
