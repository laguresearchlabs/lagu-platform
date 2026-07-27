package com.lagu.platform.vendor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorProfileResponse {
    private UUID tenantId;
    private UUID recordId;
    private String businessName;
    private String status;
    private String country;
    private KycChecklistDto kycChecklist;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
