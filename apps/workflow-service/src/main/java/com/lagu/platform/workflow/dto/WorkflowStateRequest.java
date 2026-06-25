package com.lagu.platform.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

@Data
public class WorkflowStateRequest {

    @NotBlank
    private String name;

    @NotBlank
    private String label;

    private String  description;
    private boolean terminal     = false;
    private int     displayOrder = 0;
    private String  color;
}
