package com.lagu.platform.schema.dto;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record SearchDefinitionResponse(
        UUID id,
        String listingType,
        List<String> consumerFacets,
        List<String> adminFacets,
        String defaultSortField,
        String defaultSortDir,
        String boostField,
        boolean active,
        OffsetDateTime createdAt,
        OffsetDateTime updatedAt
) {}
