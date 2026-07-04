package com.lagu.platform.workflow.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.WorkflowEvent;
import com.lagu.platform.workflow.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

/**
 * Stages WorkflowEvents in the transactional outbox ({@code workflow_outbox}) inside the
 * caller's transaction; {@link OutboxRelay} delivers committed rows to Kafka. This keeps the
 * old AFTER_COMMIT guarantee (a rolled-back state change never emits an event) and adds the
 * one it lacked: a send failure or crash after commit can no longer lose the event.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowEventPublisher {

    private final OutboxEventRepository outboxRepository;
    private final ObjectMapper objectMapper;

    public void publishTransitioned(WorkflowDefinition wf, RecordWorkflowState rws,
                                    WorkflowTransition tx, UUID actorId, String comment) {
        publish("TRANSITIONED", wf, rws, tx, null, null, actorId, comment);
    }

    public void publishTransitionRejected(WorkflowDefinition wf, RecordWorkflowState rws,
                                          WorkflowTransition tx, UUID actorId, String reason) {
        publish("TRANSITION_REJECTED", wf, rws, tx, null, null, actorId, reason);
    }

    public void publishApprovalRequested(WorkflowDefinition wf, RecordWorkflowState rws,
                                         WorkflowTransition tx, ApprovalInstance instance,
                                         UUID actorId) {
        publish("APPROVAL_REQUESTED", wf, rws, tx, instance.getId(),
                String.valueOf(instance.getCurrentStep()), actorId, null);
    }

    public void publishApprovalStepCompleted(ApprovalInstance instance, int step, UUID actorId) {
        WorkflowEvent event = WorkflowEvent.builder()
                .eventType("APPROVAL_STEP_COMPLETED")
                .recordId(instance.getRecordId())
                .orgId(instance.getOrgId())
                .approvalInstanceId(instance.getId())
                .approvalStep(String.valueOf(step))
                .actorUserId(actorId)
                .occurredAt(Instant.now())
                .build();
        send(instance.getOrgId().toString(), event);
    }

    public void publishApprovalRejected(WorkflowDefinition wf, RecordWorkflowState rws,
                                        ApprovalInstance instance, UUID actorId) {
        WorkflowEvent event = WorkflowEvent.builder()
                .eventType("APPROVAL_REJECTED")
                .recordId(rws.getRecordId())
                .orgId(rws.getOrgId())
                .objectType(rws.getObjectType())
                .workflowId(wf.getId())
                .fromState(rws.getCurrentState())
                .approvalInstanceId(instance.getId())
                .actorUserId(actorId)
                .occurredAt(Instant.now())
                .build();
        send(rws.getOrgId().toString(), event);
    }

    private void publish(String eventType, WorkflowDefinition wf, RecordWorkflowState rws,
                         WorkflowTransition tx, UUID approvalInstanceId, String approvalStep,
                         UUID actorId, String comment) {
        WorkflowEvent event = WorkflowEvent.builder()
                .eventType(eventType)
                .recordId(rws.getRecordId())
                .orgId(rws.getOrgId())
                .objectType(rws.getObjectType())
                .workflowId(wf.getId())
                .fromState(tx.getFromState())
                .toState(tx.getToState())
                .triggerName(tx.getTriggerName())
                .comment(comment)
                .approvalInstanceId(approvalInstanceId)
                .approvalStep(approvalStep)
                .actorUserId(actorId)
                .occurredAt(Instant.now())
                .build();
        send(rws.getOrgId().toString(), event);
    }

    private void send(String key, WorkflowEvent event) {
        String partitionKey = event.getRecordId() != null ? key + ":" + event.getRecordId() : key;
        OutboxEvent row = new OutboxEvent();
        row.setTopic(PlatformTopics.WORKFLOW_EVENTS);
        row.setEventKey(partitionKey);
        row.setPayloadType(event.getClass().getName());
        try {
            row.setPayload(objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Must propagate: failing to stage the event has to roll back the state change,
            // otherwise the workflow state and its consumers silently diverge.
            throw new IllegalStateException("Could not serialize WorkflowEvent for outbox", e);
        }
        outboxRepository.save(row);
    }
}
