package com.lagu.platform.metadata.dto;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

@Data
@Builder
public class CountryValidationDto {
    private UUID id;
    private String country;
    private Map<String, Object> rules;
    private String currency;
    private String taxLabel;
    private String dialCode;
}
