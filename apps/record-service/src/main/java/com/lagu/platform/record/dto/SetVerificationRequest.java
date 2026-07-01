package com.lagu.platform.record.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.time.OffsetDateTime;

@Data
public class SetVerificationRequest {

    @NotBlank
    private String tier;   // NONE / BASIC / PREMIUM

    private OffsetDateTime expiresAt;

    private String notes;
}
