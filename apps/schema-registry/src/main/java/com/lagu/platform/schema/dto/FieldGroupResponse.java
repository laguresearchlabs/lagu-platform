package com.lagu.platform.schema.dto;

import java.util.List;
import java.util.UUID;

public record FieldGroupResponse(
        UUID id,
        String name,
        String label,
        String description,
        List<FieldResponse> fields
) {}
