package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class RelationshipDefinitionResponse {
    private UUID id;
    private UUID orgId;
    private String name;
    private String label;
    private String sourceObjectType;
    private String targetObjectType;
    private String relationshipType;
    private boolean required;
    private boolean cascadeDelete;
    private boolean active;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
