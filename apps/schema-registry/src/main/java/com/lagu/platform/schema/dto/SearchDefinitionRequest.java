package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record SearchDefinitionRequest(
        @NotBlank String listingType,
        List<String> consumerFacets,
        List<String> adminFacets,
        String defaultSortField,
        String defaultSortDir,
        String boostField
) {}
