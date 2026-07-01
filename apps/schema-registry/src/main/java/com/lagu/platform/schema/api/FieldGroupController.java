package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.dto.FieldGroupRequest;
import com.lagu.platform.schema.dto.FieldGroupResponse;
import com.lagu.platform.schema.service.FieldGroupService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/field-groups")
@RequiredArgsConstructor
public class FieldGroupController {

    private final FieldGroupService fieldGroupService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<FieldGroupResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(fieldGroupService.listPlatformLevel()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FieldGroupResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(fieldGroupService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FieldGroupResponse>> create(
            @Valid @RequestBody FieldGroupRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(fieldGroupService.create(req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FieldGroupResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody FieldGroupRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(fieldGroupService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        fieldGroupService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
