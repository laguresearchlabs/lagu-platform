package com.lagu.platform.metadata.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class RelationshipDefinitionRequest {

    @NotBlank
    @Pattern(regexp = "^[A-Z][A-Z0-9_]*$", message = "Name must be UPPER_SNAKE_CASE")
    private String name;

    @NotBlank
    private String label;

    @NotBlank
    private String sourceObjectType;

    @NotBlank
    private String targetObjectType;

    @NotBlank
    @Pattern(regexp = "ONE_TO_ONE|ONE_TO_MANY|MANY_TO_MANY|PARENT_CHILD",
             message = "Must be ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY, or PARENT_CHILD")
    private String relationshipType;

    private boolean required;
    private boolean cascadeDelete;
}
