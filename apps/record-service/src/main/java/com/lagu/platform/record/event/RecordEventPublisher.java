package com.lagu.platform.record.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;

@Component
@RequiredArgsConstructor
@Slf4j
public class RecordEventPublisher {

    private final KafkaTemplate<String, Object> kafkaTemplate;

    public void publishCreated(Record record) {
        publish(recordKey(record), RecordEvent.builder()
                .eventType("CREATED")
                .recordId(record.getId())
                .orgId(record.getOrgId())
                .objectType(record.getObjectType())
                .currentStatus(record.getStatus())
                .data(record.getData())
                .changedBy(record.getCreatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishUpdated(Record record) {
        publish(recordKey(record), RecordEvent.builder()
                .eventType("UPDATED")
                .recordId(record.getId())
                .orgId(record.getOrgId())
                .objectType(record.getObjectType())
                .currentStatus(record.getStatus())
                .data(record.getData())
                .changedBy(record.getUpdatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishDeleted(Record record) {
        publish(recordKey(record), RecordEvent.builder()
                .eventType("DELETED")
                .recordId(record.getId())
                .orgId(record.getOrgId())
                .objectType(record.getObjectType())
                .previousStatus(record.getStatus())
                .currentStatus("DELETED")
                .changedBy(record.getUpdatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishTransitionRequested(Record record, String trigger, String comment,
                                           PlatformSecurityContext ctx) {
        publish(recordKey(record), RecordEvent.builder()
                .eventType("STATUS_TRANSITION_REQUESTED")
                .recordId(record.getId())
                .orgId(record.getOrgId())
                .objectType(record.getObjectType())
                .currentStatus(record.getStatus())
                .triggerName(trigger)
                .comment(comment)
                .changedBy(ctx != null ? ctx.getUserId() : null)
                .occurredAt(Instant.now())
                .build());
    }

    public void publishStatusChanged(Record record, String previousStatus) {
        publish(recordKey(record), RecordEvent.builder()
                .eventType("STATUS_CHANGED")
                .recordId(record.getId())
                .orgId(record.getOrgId())
                .objectType(record.getObjectType())
                .previousStatus(previousStatus)
                .currentStatus(record.getStatus())
                .changedBy(record.getUpdatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    private void publish(String key, RecordEvent event) {
        kafkaTemplate.send(PlatformTopics.RECORD_EVENTS, key, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish RecordEvent type={} recordId={}",
                                event.getEventType(), event.getRecordId(), ex);
                    }
                });
    }

    private String recordKey(Record record) {
        return record.getOrgId() + ":" + record.getId();
    }
}
