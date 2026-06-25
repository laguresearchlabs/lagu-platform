package com.lagu.platform.workflow.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Data @Builder
public class WorkflowDefinitionResponse {
    private UUID   id;
    private UUID   orgId;
    private String name;
    private String label;
    private String objectType;
    private String initialStatus;
    private boolean active;
    private List<WorkflowStateResponse>      states;
    private List<WorkflowTransitionResponse> transitions;
    private OffsetDateTime createdAt;
    private OffsetDateTime updatedAt;
}
