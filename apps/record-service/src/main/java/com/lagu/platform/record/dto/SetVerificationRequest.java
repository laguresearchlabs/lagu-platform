package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class SetVerificationRequest {

    /** Must match the tier ladder seeded in schema-registry's TierConfiguration. */
    @NotBlank
    @Pattern(regexp = "(?i)NONE|BASIC|ENHANCED|PREMIUM",
             message = "tier must be one of NONE, BASIC, ENHANCED, PREMIUM")
    private String tier;

    private OffsetDateTime expiresAt;

    private String notes;
}
