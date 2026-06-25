package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data
@Builder
public class EntityResponse {

    private UUID   id;
    private UUID   orgId;
    private String name;
    private String label;
    private String description;
    private boolean active;
    private List<EntityAttributeResponse> attributes;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Data
    @Builder
    public static class EntityAttributeResponse {
        private UUID   attributeId;
        private String attributeName;
        private String attributeLabel;
        private int    displayOrder;
        private boolean required;
    }
}
