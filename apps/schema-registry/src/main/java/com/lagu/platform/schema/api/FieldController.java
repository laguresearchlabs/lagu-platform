package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.dto.FieldRequest;
import com.lagu.platform.schema.dto.FieldResponse;
import com.lagu.platform.schema.service.FieldService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/fields")
@RequiredArgsConstructor
public class FieldController {

    private final FieldService fieldService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<FieldResponse>>> list() {
        return ResponseEntity.ok(ApiResponse.ok(fieldService.listPlatformLevel()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<FieldResponse>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(fieldService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<FieldResponse>> create(@Valid @RequestBody FieldRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(fieldService.create(req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<FieldResponse>> update(
            @PathVariable UUID id,
            @Valid @RequestBody FieldRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(fieldService.update(id, req)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> deactivate(@PathVariable UUID id) {
        fieldService.deactivate(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
