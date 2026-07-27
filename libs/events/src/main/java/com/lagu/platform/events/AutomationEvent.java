package com.lagu.platform.events;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class AutomationEvent implements PlatformEvent {

    /**
     * Unique per logical publish (not per Kafka delivery attempt) — generated once when the
     * event is built and preserved across JSON (de)serialization, so a Kafka redelivery of the
     * same message carries the same id. Consumers that must not process the same logical event
     * twice (notification-service, specifically) can use this as a dedup key. Defaults via
     * {@code @Builder.Default} so every existing {@code AutomationEvent.builder()...build()}
     * call site gets one automatically without needing to set it explicitly.
     */
    @Builder.Default
    private UUID eventId = UUID.randomUUID();

    /** TRIGGER_FIRED | ACTION_SUCCEEDED | ACTION_FAILED | ESCALATION_FIRED */
    private String              eventType;

    private UUID                tenantId;
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
