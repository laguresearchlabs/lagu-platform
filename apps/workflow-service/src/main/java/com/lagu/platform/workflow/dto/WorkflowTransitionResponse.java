package com.lagu.platform.workflow.dto;

import lombok.Builder;
import lombok.Data;

import java.util.List;
import java.util.UUID;

@Data @Builder
public class WorkflowTransitionResponse {
    private UUID         id;
    private String       fromState;
    private String       toState;
    private String       triggerName;
    private String       triggerLabel;
    private List<String> allowedRoles;
    private boolean      requiresApproval;
    private UUID         approvalDefId;
}
