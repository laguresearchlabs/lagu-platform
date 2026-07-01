package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record ListingTypeRequest(
        @NotBlank String name,
        @NotBlank String label,
        String description,
        String icon,
        String color,
        boolean publishable,
        boolean consumerSearchable,
        List<SectionRequest> sections
) {
    public record SectionRequest(
            String fieldGroupName,
            String label,
            @NotBlank String sectionKey,
            int displayOrder,
            boolean collapsible
    ) {}
}
