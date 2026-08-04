package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record FieldGroupRequest(
        @NotBlank String name,
        @NotBlank String label,
        String description,
        List<FieldGroupEntryRequest> entries
) {
    public record FieldGroupEntryRequest(
            String fieldName,
            int displayOrder,
            boolean required,
            /** Conditional visibility rule; null = always visible. Applies in every listing type
             *  composing this group — see ADR-19. Validated on write. */
            Map<String, Object> visibleWhen
    ) {}
}
