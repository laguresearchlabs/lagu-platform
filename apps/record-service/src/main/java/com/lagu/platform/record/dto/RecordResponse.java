package com.lagu.platform.record.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class RecordResponse {

    private UUID   id;
    private UUID   orgId;
    private String objectType;
    private String status;
    private Map<String, Object> data;
    private UUID   createdBy;
    private UUID   updatedBy;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;

    // Populated when record has a verification entry
    private String verificationTier;
    private String verificationStatus;
    private OffsetDateTime verificationExpiresAt;
}
