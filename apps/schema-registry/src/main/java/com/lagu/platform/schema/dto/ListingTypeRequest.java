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
        /** Client presentation config; see ListingTypeResponse.config. Null leaves it unchanged. */
        Map<String, Object> config,
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
            Map<String, Object> visibleWhen,
            /**
             * Who this section is for: PUBLIC, GUEST or HOST. Null leaves the entity default
             * (GUEST), which is what every section seeded before the column existed meant.
             *
             * <p>Distinct from {@link #visibleWhen}: that is a rule about the record's other
             * values, this is a rule about the reader. It has been readable by consumers since
             * the column was added, but writable by nobody — so the only way to mark a section
             * PUBLIC was an UPDATE against the database, which is not a thing an admin can do.
             */
            String audience
    ) {}
}
