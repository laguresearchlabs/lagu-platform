package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.domain.DocumentTypeDefinition;
import com.lagu.platform.metadata.domain.DocumentTypeDefinitionRepository;
import com.lagu.platform.metadata.dto.DocumentTypeDto;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/document-types")
@RequiredArgsConstructor
public class DocumentTypeController {

    private final DocumentTypeDefinitionRepository repository;

    @GetMapping
    public ResponseEntity<ApiResponse<List<DocumentTypeDto>>> list(
            @RequestParam(required = false) String objectType) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        List<DocumentTypeDefinition> types;
        if (objectType != null && ctx != null && ctx.getOrgId() != null) {
            types = repository.findForOrgAndObjectType(ctx.getOrgId(), objectType.toUpperCase());
        } else if (objectType != null) {
            types = repository.findPlatformByObjectType(objectType.toUpperCase());
        } else {
            types = repository.findByOrgIdIsNullAndActiveTrue();
        }
        return ResponseEntity.ok(ApiResponse.ok(types.stream().map(this::toDto).toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentTypeDto>> getById(@PathVariable UUID id) {
        return repository.findById(id)
                .map(d -> ResponseEntity.ok(ApiResponse.ok(toDto(d))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<DocumentTypeDto>> create(
            @RequestBody DocumentTypeDefinition req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx != null && ctx.getOrgId() != null) req.setOrgId(ctx.getOrgId());
        DocumentTypeDefinition saved = repository.save(req);
        return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.ok(toDto(saved)));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<DocumentTypeDto>> update(
            @PathVariable UUID id,
            @RequestBody DocumentTypeDefinition req) {
        return repository.findById(id).map(existing -> {
            req.setId(id);
            req.setCreatedAt(existing.getCreatedAt());
            req.setOrgId(existing.getOrgId());
            return ResponseEntity.ok(ApiResponse.ok(toDto(repository.save(req))));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        repository.findById(id).ifPresent(d -> {
            d.setActive(false);
            repository.save(d);
        });
        return ResponseEntity.noContent().build();
    }

    private DocumentTypeDto toDto(DocumentTypeDefinition d) {
        return DocumentTypeDto.builder()
                .id(d.getId())
                .code(d.getCode())
                .label(d.getLabel())
                .description(d.getDescription())
                .objectType(d.getObjectType())
                .required(d.isRequired())
                .expiryTracked(d.isExpiryTracked())
                .allowedMimeTypes(d.getAllowedMimeTypes())
                .maxSizeMb(d.getMaxSizeMb())
                .displayOrder(d.getDisplayOrder())
                .build();
    }
}
