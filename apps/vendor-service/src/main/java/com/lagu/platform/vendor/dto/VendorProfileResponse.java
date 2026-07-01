package com.lagu.platform.vendor.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class VendorProfileResponse {
    private UUID orgId;
    private UUID recordId;
    private String businessName;
    private String status;
    private String country;
    private KycChecklistDto kycChecklist;
    private Instant createdAt;
    private Instant updatedAt;
}
