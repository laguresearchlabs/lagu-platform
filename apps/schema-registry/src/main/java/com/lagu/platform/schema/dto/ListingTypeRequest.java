package com.lagu.platform.schema.dto;

import com.lagu.platform.schema.domain.ListingTypeKind;
import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record ListingTypeRequest(
        @NotBlank String name,
        @NotBlank String label,
        String description,
        String icon,
        String color,
        /** Null defaults to LISTING — an unclassified type is never silently treated as an event. */
        ListingTypeKind kind,
        boolean publishable,
        boolean consumerSearchable,
        List<SectionRequest> sections
) {
    public record SectionRequest(
            String fieldGroupName,
            String label,
            @NotBlank String sectionKey,
            int displayOrder,
            boolean collapsible,
            /** Conditional visibility rule; null = always visible. Validated on write. */
            Map<String, Object> visibleWhen
    ) {}
}
