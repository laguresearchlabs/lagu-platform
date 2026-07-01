package com.lagu.platform.metadata.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Builder;
import lombok.Data;

import java.math.BigDecimal;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class TierConfigDto {
    private UUID id;
    private String tierName;
    private String objectType;
    private BigDecimal commissionRate;
    private Integer maxActiveBookings;
    private BigDecimal searchBoostFactor;
    private int responseSlaHours;
    private Map<String, Object> features;
}
