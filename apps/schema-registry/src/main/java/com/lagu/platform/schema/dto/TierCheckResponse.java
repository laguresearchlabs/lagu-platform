package com.lagu.platform.schema.dto;

import java.util.List;

public record TierCheckResponse(
        String listingType,
        String targetTier,
        boolean eligible,
        List<RuleCheckItem> checks
) {
    public record RuleCheckItem(
            String displayName,
            boolean satisfied,
            String hint
    ) {}
}
