package com.lagu.platform.automation.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

import java.util.Map;

@Data
public class CreateActionRequest {

    @NotBlank
    private String actionType;

    private Integer executionOrder;

    @NotNull
    private Map<String, Object> config;

    private Boolean continueOnFailure;

    private Boolean isActive;
}
