package com.lagu.platform.automation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.events.WorkflowEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
@Slf4j
public class AutomationEventParser {

    private final ObjectMapper objectMapper;

    public AutomationEventContext parseRecordEvent(String json) {
        try {
            RecordEvent e = objectMapper.readValue(json, RecordEvent.class);
            return AutomationEventContext.builder()
                    .eventType(toAutomationEventType(e.getEventType()))
                    .orgId(e.getOrgId())
                    .recordId(e.getRecordId())
                    .objectType(e.getObjectType())
                    .previousStatus(e.getPreviousStatus())
                    .currentStatus(e.getCurrentStatus())
                    .data(e.getData())
                    .changedBy(e.getChangedBy())
                    .build();
        } catch (Exception ex) {
            log.warn("Failed to parse RecordEvent: {}", ex.getMessage());
            return null;
        }
    }

    public AutomationEventContext parseWorkflowEvent(String json) {
        try {
            WorkflowEvent e = objectMapper.readValue(json, WorkflowEvent.class);
            String autoType = switch (e.getEventType()) {
                case "APPROVAL_REQUESTED"       -> "APPROVAL_REQUESTED";
                case "APPROVAL_STEP_COMPLETED"  -> "APPROVAL_REQUESTED";  // interim — still pending
                case "APPROVAL_REJECTED"        -> "APPROVAL_REJECTED";
                default                         -> null;
            };
            if (autoType == null) return null;

            return AutomationEventContext.builder()
                    .eventType(autoType)
                    .orgId(e.getOrgId())
                    .recordId(e.getRecordId())
                    .objectType(e.getObjectType())
                    .currentStatus(e.getToState())
                    .approvalInstanceId(e.getApprovalInstanceId())
                    .changedBy(e.getActorUserId())
                    .build();
        } catch (Exception ex) {
            log.warn("Failed to parse WorkflowEvent: {}", ex.getMessage());
            return null;
        }
    }

    private String toAutomationEventType(String recordEventType) {
        return switch (recordEventType) {
            case "CREATED"       -> "RECORD_CREATED";
            case "UPDATED"       -> "RECORD_UPDATED";
            case "STATUS_CHANGED"-> "RECORD_STATUS_CHANGED";
            case "DELETED"       -> "RECORD_DELETED";
            default              -> recordEventType;
        };
    }
}
