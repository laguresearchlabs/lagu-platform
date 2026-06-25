package com.lagu.platform.workflow.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class RecordWorkflowStatusResponse {
    private UUID       recordId;
    private String     currentState;
    private String     objectType;
    private UUID       workflowId;
    private boolean    isTerminal;
    private List<AllowedTransitionDto> allowedTransitions;
    private OffsetDateTime updatedAt;
}
