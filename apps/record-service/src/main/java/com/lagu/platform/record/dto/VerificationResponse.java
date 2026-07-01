package com.lagu.platform.record.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class VerificationResponse {

    private UUID id;
    private UUID recordId;
    private String tier;
    private String status;
    private UUID verifiedBy;
    private OffsetDateTime verifiedAt;
    private OffsetDateTime expiresAt;
    private String notes;
    private OffsetDateTime updatedAt;
}
