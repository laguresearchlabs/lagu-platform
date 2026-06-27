package com.lagu.platform.record.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.WorkflowEvent;
import com.lagu.platform.record.domain.Record;
import com.lagu.platform.record.domain.RecordAudit;
import com.lagu.platform.record.domain.RecordAuditRepository;
import com.lagu.platform.record.domain.RecordRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.Acknowledgment;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.messaging.handler.annotation.Header;
import org.springframework.messaging.handler.annotation.Payload;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowEventConsumer {

    private final RecordRepository      recordRepo;
    private final RecordAuditRepository auditRepo;
    private final RecordEventPublisher  eventPublisher;

    @KafkaListener(topics = PlatformTopics.WORKFLOW_EVENTS, groupId = "record-service")
    @Transactional
    public void onWorkflowEvent(@Payload WorkflowEvent event,
                                @Header(KafkaHeaders.RECEIVED_KEY) String key,
                                Acknowledgment ack) {
        if (!"TRANSITIONED".equals(event.getEventType())) {
            ack.acknowledge();
            return;
        }

        log.info("Applying workflow transition for record {} → {}", event.getRecordId(), event.getToState());

        recordRepo.findById(event.getRecordId()).ifPresentOrElse(record -> {
            String oldStatus = record.getStatus();
            record.setStatus(event.getToState());
            record.setUpdatedBy(event.getActorUserId());
            recordRepo.save(record);
            auditStatusChange(record, oldStatus, event.getToState(), event.getActorUserId());
            eventPublisher.publishStatusChanged(record, oldStatus);
        }, () -> log.warn("Record {} not found for workflow transition", event.getRecordId()));

        ack.acknowledge();
    }

    private void auditStatusChange(Record record, String oldStatus, String newStatus, UUID actorId) {
        RecordAudit a = new RecordAudit();
        a.setRecordId(record.getId());
        a.setAction("STATUS_CHANGED");
        a.setOldStatus(oldStatus);
        a.setNewStatus(newStatus);
        a.setChangedBy(actorId);
        auditRepo.save(a);
    }
}
