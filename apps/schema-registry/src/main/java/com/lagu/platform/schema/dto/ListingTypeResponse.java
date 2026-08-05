package com.lagu.platform.schema.dto;

import com.lagu.platform.schema.domain.ListingTypeKind;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record ListingTypeResponse(
        UUID id,
        String name,
        String label,
        String description,
        String icon,
        String color,
        ListingTypeKind kind,
        /**
         * Free-form presentation config consumers may interpret. events-ui reads a
         * `cardPresentation` key describing how listing cards render — see its
         * config/cardPresentation.ts for the shape. Kept schemaless here on purpose: this is
         * client presentation, and the registry should not have to change when a client's
         * rendering needs do.
         */
        Map<String, Object> config,
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
