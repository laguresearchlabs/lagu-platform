package com.lagu.platform.automation.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.automation.model.AutomationEventContext;
import com.lagu.platform.events.BookingEvent;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.events.WorkflowEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.HashMap;
import java.util.Map;

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
                    .tenantId(e.getTenantId())
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
                    .tenantId(e.getTenantId())
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

    /**
     * booking-service's own eventType vocabulary (INQUIRED|QUOTED|CONFIRMED|COMPLETED|CANCELLED)
     * is used directly as the trigger's eventType — no translation table needed, unlike
     * RecordEvent's CREATED->RECORD_CREATED. BookingEvent isn't schema-registry-driven, so
     * objectType is left null; dispatch() falls back to findActiveByEvent(eventType, tenantId),
     * which doesn't filter by objectType at all.
     */
    public AutomationEventContext parseBookingEvent(String json) {
        try {
            BookingEvent e = objectMapper.readValue(json, BookingEvent.class);
            Map<String, Object> data = new HashMap<>();
            data.put("consumerUserId", e.getConsumerUserId() != null ? e.getConsumerUserId().toString() : null);
            data.put("vendorTenantId", e.getVendorTenantId() != null ? e.getVendorTenantId().toString() : null);
            data.put("listingRecordId", e.getListingRecordId() != null ? e.getListingRecordId().toString() : null);
            data.put("quotedPrice", e.getQuotedPrice());
            data.put("commissionAmount", e.getCommissionAmount());
            data.put("eventDate", e.getEventDate() != null ? e.getEventDate().toString() : null);

            return AutomationEventContext.builder()
                    .eventType(e.getEventType())
                    .tenantId(e.getVendorTenantId())
                    .recordId(e.getBookingId())
                    .objectType(null)
                    .previousStatus(e.getPreviousStatus())
                    .currentStatus(e.getCurrentStatus())
                    .data(data)
                    .changedBy(e.getChangedBy())
                    .build();
        } catch (Exception ex) {
            log.warn("Failed to parse BookingEvent: {}", ex.getMessage());
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
