package com.lagu.platform.listing.service;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.WorkflowEvent;
import com.lagu.platform.listing.client.RecordServiceClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowEventConsumer {

    private static final Set<String> PUBLISH_STATES = Set.of("ACTIVE", "APPROVED", "PUBLISHED");
    private static final Set<String> UNPUBLISH_STATES = Set.of("SUSPENDED", "ARCHIVED", "REJECTED");

    private final ListingSnapshotService snapshotService;
    private final RecordServiceClient    recordClient;

    @KafkaListener(topics = PlatformTopics.WORKFLOW_EVENTS, groupId = "listing-service")
    public void onWorkflowEvent(WorkflowEvent event) {
        if (!"TRANSITIONED".equals(event.getEventType())) return;

        String toState = event.getToState() != null ? event.getToState().toUpperCase() : "";

        if (PUBLISH_STATES.contains(toState)) {
            handlePublish(event);
        } else if (UNPUBLISH_STATES.contains(toState)) {
            snapshotService.unpublishSnapshot(event.getRecordId());
        }
    }

    /**
     * Failures propagate: the container's DefaultErrorHandler retries 3× then parks the
     * event on WORKFLOW_EVENTS.DLT. Swallowing here would silently drop a listing publish —
     * the record would be ACTIVE but never appear as a consumer-facing snapshot.
     */
    private void handlePublish(WorkflowEvent event) {
        Map<String, Object> record = recordClient.getRecord(
                event.getRecordId(), event.getTenantId());

        if (record == null) {
            throw new IllegalStateException(
                    "Could not fetch record " + event.getRecordId() + " for snapshot publication");
        }

        @SuppressWarnings("unchecked")
        Map<String, Object> data = (Map<String, Object>) record.get("data");
        String verificationTier = extractString(record, "verificationTier", "NONE");

        snapshotService.publishSnapshot(
                event.getRecordId(), event.getTenantId(),
                event.getObjectType(), data,
                verificationTier);
    }

    private String extractString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultVal;
    }
}
