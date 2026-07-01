package com.lagu.platform.schema.dto;

import com.lagu.platform.schema.domain.FieldType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

import java.util.List;
import java.util.Map;

public record FieldRequest(
        @NotBlank String name,
        @NotBlank String label,
        String description,
        @NotNull FieldType fieldType,
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
        boolean arrayManageable
) {}
