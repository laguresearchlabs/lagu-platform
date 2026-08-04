package com.lagu.platform.schema.dto;

import java.util.List;
import java.util.Map;
import java.util.UUID;

public record FieldGroupResponse(
        UUID id,
        String name,
        String label,
        String description,
        /** The resolved field definitions, in display order. */
        List<FieldResponse> fields,
        /**
         * Per-placement data for each field in this group. {@link #fields} cannot carry it: a
         * FieldResponse describes the tenant-global definition, not this group's use of it.
         * Without this, an editor round-tripping a group silently discards the entry's
         * {@code required} override, its real display order, and its visibility rule.
         */
        List<EntryResponse> entries
) {
    public record EntryResponse(
            String fieldName,
            int displayOrder,
            boolean required,
            /** Conditional visibility rule; null = always visible. See ADR-18. */
            Map<String, Object> visibleWhen
    ) {}
}
