package com.lagu.platform.workflow.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class ApprovalInstanceResponse {

    private UUID   id;
    private UUID   recordId;
    private String status;
    private int    currentStep;
    private int    totalSteps;
    private String approvalType;
    private String currentApproverRole;
    private List<StepDecisionDto> decisions;
    private OffsetDateTime createdAt;
    private OffsetDateTime completedAt;

    @Data @Builder
    public static class StepDecisionDto {
        private int    stepOrder;
        private UUID   approverUserId;
        private String decision;
        private String comment;
        private OffsetDateTime decidedAt;
    }
}
