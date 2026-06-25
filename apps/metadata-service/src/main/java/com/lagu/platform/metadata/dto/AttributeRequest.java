package com.lagu.platform.metadata.dto;

import com.lagu.platform.metadata.domain.AttributeType;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

import java.util.List;
import java.util.Map;

@Data
public class AttributeRequest {

    @NotBlank
    @Pattern(regexp = "^[a-z][a-z0-9_]*$", message = "name must be snake_case")
    private String name;

    @NotBlank
    private String label;

    private String description;

    @NotNull
    private AttributeType attributeType;

    private boolean required;
    private boolean searchable;
    private boolean filterable;
    private boolean sortable;
    private boolean facetable;
    private boolean unique;

    private String defaultValue;
    private Map<String, Object> validationRules;
    private List<String> enumValues;
    private Map<String, Object> config;
}
