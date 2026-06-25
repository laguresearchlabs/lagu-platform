package com.lagu.platform.workflow.dto;

import lombok.Builder;
import lombok.Data;

@Data @Builder
public class AllowedTransitionDto {
    private String  triggerName;
    private String  triggerLabel;
    private String  toState;
    private boolean requiresApproval;
}
