package com.lagu.platform.schema.dto;

import com.lagu.platform.schema.domain.FieldType;

import java.util.List;
import java.util.Map;

public record ListingTypeSchemaDto(
        String listingType,
        int version,
        List<SectionSchemaDto> sections
) {
    public record SectionSchemaDto(
            String sectionKey,
            String label,
            int displayOrder,
            List<FieldSchemaDto> fields
    ) {}

    public record FieldSchemaDto(
            String key,
            String label,
            FieldType fieldType,
            boolean required,
            boolean promoted,
            boolean searchable,
            boolean filterable,
            boolean facetable,
            boolean rangeFilterable,
            boolean arrayManageable,
            List<String> enumValues,
            List<Map<String, Object>> itemSchema,
            Map<String, Object> validationRules
    ) {}
}
