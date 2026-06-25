package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.Map;

@Data
public class PermissionRequest {

    @NotBlank
    private String resourceType;

    @NotBlank
    private String action;

    private Map<String, Object> conditions;
}
