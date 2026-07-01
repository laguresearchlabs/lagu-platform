package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

public record TierRuleRequest(
        @NotBlank String listingType,
        @NotBlank String tier,
        @NotBlank String ruleType,
        String documentCode,
        String fieldPath,
        String operator,
        String value,
        Integer minCount,
        boolean forceOverridable,
        @NotBlank String displayName,
        String description,
        int displayOrder
) {}
