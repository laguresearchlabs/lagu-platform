package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class PermissionResponse {

    private UUID               id;
    private String             resourceType;
    private String             action;
    private Map<String,Object> conditions;
    private OffsetDateTime     createdAt;
}
