package com.lagu.platform.automation.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class CreateTriggerRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String label;

    private String description;

    @NotBlank
    private String eventType;

    private String objectType;

    private List<Map<String, Object>> conditions;

    private Boolean isActive;
}
