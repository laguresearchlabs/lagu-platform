package com.lagu.platform.vendor.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Data
@Builder
public class VendorMemberResponse {
    private UUID id;
    private UUID userId;
    private String role;
    private UUID invitedBy;
    private OffsetDateTime joinedAt;
}
