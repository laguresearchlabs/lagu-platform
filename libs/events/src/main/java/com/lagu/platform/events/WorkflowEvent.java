package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class WorkflowEvent implements PlatformEvent {

    /**
     * TRANSITIONED | APPROVAL_REQUESTED | APPROVAL_APPROVED
     * APPROVAL_REJECTED | APPROVAL_TIMEOUT | TRANSITION_REJECTED
     */
    private String eventType;

    private UUID   recordId;
    private UUID   orgId;
    private String objectType;

    private UUID   workflowId;
    private String fromState;
    private String toState;
    private String triggerName;
    private String comment;

    private UUID   approvalInstanceId;
    private String approvalStep;

    private UUID   actorUserId;
    private Instant occurredAt;
}
