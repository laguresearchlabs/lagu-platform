package com.lagu.platform.metadata.api;

import com.lagu.platform.common.dto.ApiResponse;
import com.lagu.platform.metadata.domain.CategoryDefinition;
import com.lagu.platform.metadata.domain.CategoryDefinitionRepository;
import com.lagu.platform.metadata.dto.CategoryDto;
import com.lagu.platform.security.GatewayHeaderFilter;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/v1/categories")
@RequiredArgsConstructor
public class CategoryController {

    private final CategoryDefinitionRepository repository;

    /** Returns root categories with nested children (full tree). */
    @GetMapping("/tree")
    public ResponseEntity<ApiResponse<List<CategoryDto>>> tree(
            @RequestParam(required = false) String objectType) {
        List<CategoryDefinition> roots = repository.findByParentIsNullAndOrgIdIsNullAndActiveTrue();
        if (objectType != null) {
            roots = roots.stream()
                    .filter(c -> c.getObjectType() == null
                            || c.getObjectType().equalsIgnoreCase(objectType))
                    .toList();
        }
        return ResponseEntity.ok(ApiResponse.ok(roots.stream().map(this::toDtoWithChildren).toList()));
    }

    @GetMapping
    public ResponseEntity<ApiResponse<List<CategoryDto>>> list(
            @RequestParam(required = false) String objectType) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        UUID orgId = ctx != null ? ctx.getOrgId() : null;
        List<CategoryDefinition> cats = objectType != null && orgId != null
                ? repository.findByObjectTypeForOrg(objectType.toUpperCase(), orgId)
                : repository.findByParentIsNullAndOrgIdIsNullAndActiveTrue();
        return ResponseEntity.ok(ApiResponse.ok(cats.stream().map(this::toDto).toList()));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryDto>> getById(@PathVariable UUID id) {
        return repository.findById(id)
                .map(c -> ResponseEntity.ok(ApiResponse.ok(toDtoWithChildren(c))))
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping
    public ResponseEntity<ApiResponse<CategoryDto>> create(@RequestBody CategoryDefinition req) {
        PlatformSecurityContext ctx = GatewayHeaderFilter.current();
        if (ctx != null && ctx.getOrgId() != null) req.setOrgId(ctx.getOrgId());
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(ApiResponse.ok(toDto(repository.save(req))));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ApiResponse<CategoryDto>> update(
            @PathVariable UUID id, @RequestBody CategoryDefinition req) {
        return repository.findById(id).map(existing -> {
            req.setId(id);
            req.setCreatedAt(existing.getCreatedAt());
            return ResponseEntity.ok(ApiResponse.ok(toDto(repository.save(req))));
        }).orElse(ResponseEntity.notFound().build());
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> deactivate(@PathVariable UUID id) {
        repository.findById(id).ifPresent(c -> { c.setActive(false); repository.save(c); });
        return ResponseEntity.noContent().build();
    }

    private CategoryDto toDto(CategoryDefinition c) {
        return CategoryDto.builder()
                .id(c.getId())
                .parentId(c.getParent() != null ? c.getParent().getId() : null)
                .objectType(c.getObjectType())
                .slug(c.getSlug())
                .label(c.getLabel())
                .description(c.getDescription())
                .iconUrl(c.getIconUrl())
                .displayOrder(c.getDisplayOrder())
                .build();
    }

    private CategoryDto toDtoWithChildren(CategoryDefinition c) {
        CategoryDto dto = toDto(c);
        if (c.getChildren() != null && !c.getChildren().isEmpty()) {
            dto.setChildren(c.getChildren().stream()
                    .filter(CategoryDefinition::isActive)
                    .map(this::toDtoWithChildren)
                    .toList());
        }
        return dto;
    }
}
