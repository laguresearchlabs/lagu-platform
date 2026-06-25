package com.lagu.platform.workflow.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data
public class WorkflowTransitionRequest {

    @NotBlank private String fromState;
    @NotBlank private String toState;
    @NotBlank private String triggerName;

    private String       triggerLabel;
    private List<String> allowedRoles;
    private boolean      requiresApproval = false;
    private UUID         approvalDefId;
}
