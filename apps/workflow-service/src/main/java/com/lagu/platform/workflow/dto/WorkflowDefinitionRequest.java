package com.lagu.platform.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import lombok.Data;

@Data
public class WorkflowDefinitionRequest {

    @NotBlank @Size(max = 100)
    private String name;

    @NotBlank @Size(max = 200)
    private String label;

    @NotBlank @Size(max = 100)
    private String objectType;

    private String initialStatus = "DRAFT";
}
