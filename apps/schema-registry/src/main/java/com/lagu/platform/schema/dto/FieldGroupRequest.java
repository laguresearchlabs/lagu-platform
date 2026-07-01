package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record FieldGroupRequest(
        @NotBlank String name,
        @NotBlank String label,
        String description,
        List<FieldGroupEntryRequest> entries
) {
    public record FieldGroupEntryRequest(
            String fieldName,
            int displayOrder,
            boolean required
    ) {}
}
