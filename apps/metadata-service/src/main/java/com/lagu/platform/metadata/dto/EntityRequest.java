package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class EntityRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "name must be snake_case")
    private String name;

    @NotBlank
    private String label;

    private String description;
}
