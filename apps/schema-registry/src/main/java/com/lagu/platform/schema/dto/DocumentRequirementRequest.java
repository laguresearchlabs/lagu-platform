package com.lagu.platform.schema.dto;

import jakarta.validation.constraints.NotBlank;

import java.util.List;

public record DocumentRequirementRequest(
        String listingType,
        @NotBlank String code,
        @NotBlank String label,
        String description,
        boolean required,
        List<String> requiredForTiers,
        boolean expiryTracked,
        List<String> allowedMimeTypes,
        int maxSizeMb,
        int displayOrder
) {}
