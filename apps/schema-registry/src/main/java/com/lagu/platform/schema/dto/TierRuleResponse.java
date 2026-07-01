package com.lagu.platform.schema.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record TierRuleResponse(
        UUID id,
        String listingType,
        String tier,
        String ruleType,
        String documentCode,
        String fieldPath,
        String operator,
        String value,
        Integer minCount,
        boolean forceOverridable,
        String displayName,
        String description,
        int displayOrder,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
