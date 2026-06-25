package com.lagu.platform.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class ApprovalDecisionRequest {

    @NotBlank
    @Pattern(regexp = "APPROVED|REJECTED")
    private String decision;

    private String comment;
}
