package com.lagu.platform.schema.dto;

import com.lagu.platform.schema.domain.FieldType;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FieldResponse(
        UUID id,
        UUID orgId,
        String name,
        String label,
        String description,
        FieldType fieldType,
        List<String> enumValues,
        List<Map<String, Object>> itemSchema,
        String referenceType,
        boolean required,
        boolean unique,
        Map<String, Object> validationRules,
        String defaultValue,
        boolean searchable,
        boolean filterable,
        boolean sortable,
        boolean facetable,
        boolean promoted,
        boolean rangeFilterable,
        boolean arrayManageable,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
