package com.lagu.platform.schema.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

public record SchemaVersionResponse(
        UUID id,
        String listingType,
        int version,
        String changeClassification,
        String changeSummary,
        String publishedBy,
        OffsetDateTime publishedAt
) {}
