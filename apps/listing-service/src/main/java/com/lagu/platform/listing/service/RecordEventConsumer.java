package com.lagu.platform.listing.service;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.stereotype.Component;

/**
 * Before this, listing-service consumed only WorkflowEvent (TRANSITIONED) — never RecordEvent —
 * so an already-published listing's own data (price, address, phone, ...) never refreshed after
 * the initial publish, and a deleted record's snapshot/public-search entry never went away.
 * Neither gap needed a malicious actor: any ordinary "vendor edits their live listing" or
 * "vendor's listing gets deleted" flow hit it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecordEventConsumer {

    private final ListingSnapshotService snapshotService;

    @KafkaListener(topics = PlatformTopics.RECORD_EVENTS, groupId = "listing-service-records")
    public void onRecordEvent(RecordEvent event, Acknowledgment ack) {
        switch (event.getEventType()) {
            case "UPDATED" -> snapshotService.refreshSnapshotData(event.getRecordId(), event.getData());
            case "DELETED" -> snapshotService.unpublishSnapshot(event.getRecordId());
            default -> { /* CREATED/STATUS_CHANGED are handled via the workflow transition path */ }
        }
        ack.acknowledge();
    }
}
