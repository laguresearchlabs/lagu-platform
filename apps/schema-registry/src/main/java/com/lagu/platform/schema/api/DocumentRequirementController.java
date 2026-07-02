package com.lagu.platform.schema.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.schema.domain.DocumentRequirement;
import com.lagu.platform.schema.dto.DocumentRequirementRequest;
import com.lagu.platform.schema.service.DocumentRequirementService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-requirements")
@RequiredArgsConstructor
public class DocumentRequirementController {

    private final DocumentRequirementService documentRequirementService;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentRequirement>>> list(
            @RequestParam(required = false) String listingType) {
        return ResponseEntity.ok(ApiResponse.ok(documentRequirementService.list(listingType)));
    }

    /** Full platform-level catalog regardless of listingType — used by document-service. */
    @GetMapping("/catalog")
    public ResponseEntity<ApiResponse<List<DocumentRequirement>>> catalog() {
        return ResponseEntity.ok(ApiResponse.ok(documentRequirementService.catalog()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentRequirement>> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(documentRequirementService.getById(id)));
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentRequirement>> create(
            @Valid @RequestBody DocumentRequirementRequest req) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(documentRequirementService.create(req)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentRequirement>> update(
            @PathVariable UUID id,
            @Valid @RequestBody DocumentRequirementRequest req) {
        return ResponseEntity.ok(ApiResponse.ok(documentRequirementService.update(id, req)));
    }

    @PatchMapping("/{id}/active")
    public ResponseEntity<ApiResponse<DocumentRequirement>> toggleActive(@PathVariable UUID id) {
        return ResponseEntity.ok(ApiResponse.ok(documentRequirementService.toggleActive(id)));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<ApiResponse<Void>> delete(@PathVariable UUID id) {
        documentRequirementService.delete(id);
        return ResponseEntity.ok(ApiResponse.ok(null));
    }
}
