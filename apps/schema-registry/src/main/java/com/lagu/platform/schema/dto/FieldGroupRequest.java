package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;
import java.util.Map;

public record FieldGroupRequest(
        @NotBlank String name,
        @NotBlank String label,
        String description,
        List<FieldGroupEntryRequest> entries
) {
    public record FieldGroupEntryRequest(
            String fieldName,
            int displayOrder,
            /**
             * This placement's opinion on requiredness, which wins over the field definition.
             *
             * Boxed so it carries three states: null means "no opinion, inherit the definition".
             * It was a primitive, and resolution was {@code definition || entry}, so unchecking it
             * on a globally-required field silently did nothing while the UI called it an override.
             */
            Boolean required,
            /** Conditional visibility rule; null = always visible. Applies in every listing type
             *  composing this group — see ADR-19. Validated on write. */
            Map<String, Object> visibleWhen
    ) {}
}
