package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.Map;

@Data
public class ObjectTypeRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "name must be UPPER_SNAKE_CASE")
    private String name;

    @NotBlank
    private String label;

    private String description;
    private String icon;
    private String color;
    private boolean publishable;
    private Map<String, Object> config;
}
