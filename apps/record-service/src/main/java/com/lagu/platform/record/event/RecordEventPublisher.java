package com.lagu.platform.record.event;

import com.lagu.platform.common.outbox.TransactionalOutbox;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.events.VerificationEvent;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.security.PlatformSecurityContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;

/**
 * Stages platform events in the transactional outbox ({@code record_outbox}) rather than
 * sending to Kafka directly. Every publish method is called inside the service-layer
 * transaction that makes the corresponding record change, so the event and the change
 * commit or roll back together; the shared relay delivers committed events to Kafka.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class RecordEventPublisher {

    private final TransactionalOutbox outbox;

    public void publishCreated(Record record) {
        enqueue(PlatformTopics.RECORD_EVENTS, recordKey(record), RecordEvent.builder()
                .eventType("CREATED")
                .recordId(record.getId())
                .tenantId(record.getTenantId())
                .objectType(record.getObjectType())
                .currentStatus(record.getStatus())
                .data(record.getData())
                .changedBy(record.getCreatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishUpdated(Record record) {
        enqueue(PlatformTopics.RECORD_EVENTS, recordKey(record), RecordEvent.builder()
                .eventType("UPDATED")
                .recordId(record.getId())
                .tenantId(record.getTenantId())
                .objectType(record.getObjectType())
                .currentStatus(record.getStatus())
                .data(record.getData())
                .changedBy(record.getUpdatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishDeleted(Record record) {
        enqueue(PlatformTopics.RECORD_EVENTS, recordKey(record), RecordEvent.builder()
                .eventType("DELETED")
                .recordId(record.getId())
                .tenantId(record.getTenantId())
                .objectType(record.getObjectType())
                .previousStatus(record.getStatus())
                .currentStatus("DELETED")
                .changedBy(record.getUpdatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishTransitionRequested(Record record, String trigger, String comment,
                                           java.util.Map<String, Object> context,
                                           PlatformSecurityContext ctx) {
        enqueue(PlatformTopics.RECORD_EVENTS, recordKey(record), RecordEvent.builder()
                .eventType("STATUS_TRANSITION_REQUESTED")
                .recordId(record.getId())
                .tenantId(record.getTenantId())
                .objectType(record.getObjectType())
                .currentStatus(record.getStatus())
                .triggerName(trigger)
                .comment(comment)
                .context(context)
                .changedBy(ctx != null ? ctx.getUserId() : null)
                .changedByRoles(ctx != null ? ctx.getRoles() : null)
                .occurredAt(Instant.now())
                .build());
    }

    public void publishStatusChanged(Record record, String previousStatus) {
        enqueue(PlatformTopics.RECORD_EVENTS, recordKey(record), RecordEvent.builder()
                .eventType("STATUS_CHANGED")
                .recordId(record.getId())
                .tenantId(record.getTenantId())
                .objectType(record.getObjectType())
                .previousStatus(previousStatus)
                .currentStatus(record.getStatus())
                .changedBy(record.getUpdatedBy())
                .occurredAt(Instant.now())
                .build());
    }

    public void publishVerificationChanged(Record record, String previousTier, String newTier,
                                           PlatformSecurityContext ctx) {
        String eventType = "EXPIRED".equals(newTier) || "REVOKED".equals(newTier)
                ? newTier : "TIER_CHANGED";
        enqueue(PlatformTopics.VERIFICATION_EVENTS, recordKey(record), VerificationEvent.builder()
                .eventType(eventType)
                .recordId(record.getId())
                .tenantId(record.getTenantId())
                .objectType(record.getObjectType())
                .previousTier(previousTier)
                .newTier(newTier)
                .changedBy(ctx != null ? ctx.getUserId() : null)
                .occurredAt(Instant.now())
                .build());
    }

    private void enqueue(String topic, String key, Object event) {
        outbox.stage(topic, key, event);
    }

    private String recordKey(Record record) {
        return record.getTenantId() + ":" + record.getId();
    }
}
