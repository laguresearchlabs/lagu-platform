package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

import java.math.BigDecimal;
import java.util.Map;

public record TierConfigRequest(
        @NotBlank String tierName,
        String listingType,
        BigDecimal commissionRate,
        Integer maxActiveBookings,
        BigDecimal searchBoostFactor,
        int responseSlaHours,
        int expiryDays,
        Map<String, Object> features
) {}
