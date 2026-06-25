package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class ObjectTypeResponse {

    private UUID   id;
    private UUID   orgId;
    private String name;
    private String label;
    private String description;
    private String icon;
    private String color;
    private boolean publishable;
    private boolean active;
    private Map<String, Object> config;
    private List<SectionResponse> sections;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    @Data
    @Builder
    public static class SectionResponse {
        private UUID   sectionId;
        private UUID   entityId;
        private String entityName;
        private String label;
        private int    displayOrder;
        private boolean collapsible;
    }
}
