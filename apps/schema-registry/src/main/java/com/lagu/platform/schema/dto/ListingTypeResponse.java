package com.lagu.platform.schema.dto;

import java.util.List;
import java.util.UUID;

public record ListingTypeResponse(
        UUID id,
        String name,
        String label,
        String description,
        String icon,
        String color,
        boolean publishable,
        boolean consumerSearchable,
        boolean active,
        int currentVersion,
        List<SectionResponse> sections
) {
    public record SectionResponse(
            UUID id,
            String sectionKey,
            String label,
            int displayOrder,
            boolean collapsible,
            FieldGroupResponse fieldGroup
    ) {}
}
