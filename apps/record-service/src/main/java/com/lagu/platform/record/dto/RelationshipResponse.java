package com.lagu.platform.record.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class RelationshipResponse {

    private UUID              id;
    private String            relationshipName;
    private UUID              sourceRecordId;
    private UUID              targetRecordId;
    private String            targetObjectType;
    private String            targetStatus;
    private Map<String,Object> targetData;
    private OffsetDateTime    createdAt;
}
