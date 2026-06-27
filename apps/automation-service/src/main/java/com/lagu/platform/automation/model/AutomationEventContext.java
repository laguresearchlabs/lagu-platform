package com.lagu.platform.automation.model;

import lombok.Builder;
import lombok.Data;

import java.util.Map;
import java.util.UUID;

/**
 * Normalized representation of any platform event that can trigger automations.
 * Built from RecordEvent, WorkflowEvent, etc. by AutomationEventParser.
 */
@Data
@Builder
public class AutomationEventContext {

    private String             eventType;       // maps to AutomationEventType names
    private UUID               orgId;
    private UUID               recordId;
    private String             objectType;
    private String             previousStatus;
    private String             currentStatus;
    private Map<String, Object> data;

    /** Populated when a trigger is matched — used by action publishers. */
    private UUID   triggerId;
    private String triggerName;

    /** For approval events. */
    private UUID   approvalInstanceId;
    private String approvalType;

    private UUID   changedBy;
    private boolean dryRun;
}
