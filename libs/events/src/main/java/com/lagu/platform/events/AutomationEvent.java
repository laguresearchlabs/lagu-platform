package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutomationEvent implements PlatformEvent {

    /** TRIGGER_FIRED | ACTION_SUCCEEDED | ACTION_FAILED | ESCALATION_FIRED */
    private String              eventType;

    private UUID                orgId;
    private UUID                triggerId;
    private String              triggerName;
    private UUID                recordId;
    private String              objectType;

    /** Which action type was executed (null for TRIGGER_FIRED). */
    private String              actionType;
    private boolean             success;
    private String              errorMessage;

    /** Action-specific payload (e.g. notification content for SEND_NOTIFICATION). */
    private Map<String, Object> payload;

    private Instant             occurredAt;
}
