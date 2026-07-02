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

    private void handlePublish(WorkflowEvent event) {
        try {
            Map<String, Object> record = recordClient.getRecord(
                    event.getRecordId(), event.getOrgId());

            if (record == null) {
                log.warn("Could not fetch record {} for snapshot", event.getRecordId());
                return;
            }

            @SuppressWarnings("unchecked")
            Map<String, Object> data = (Map<String, Object>) record.get("data");
            String verificationTier = extractString(record, "verificationTier", "NONE");

            snapshotService.publishSnapshot(
                    event.getRecordId(), event.getOrgId(),
                    event.getObjectType(), data,
                    verificationTier);
        } catch (Exception e) {
            log.error("Failed to publish snapshot for record {}: {}", event.getRecordId(), e.getMessage(), e);
        }
    }

    private String extractString(Map<String, Object> map, String key, String defaultVal) {
        Object v = map.get(key);
        return v != null ? v.toString() : defaultVal;
    }
}
