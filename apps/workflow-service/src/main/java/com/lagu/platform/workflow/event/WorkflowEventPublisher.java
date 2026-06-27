package com.lagu.platform.workflow.event;

import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.WorkflowEvent;
import com.lagu.platform.workflow.domain.*;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.UUID;

@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowEventPublisher {

    private final KafkaTemplate<String, Object> kafka;

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
        kafka.send(PlatformTopics.WORKFLOW_EVENTS, partitionKey, event)
                .whenComplete((result, ex) -> {
                    if (ex != null) log.error("Failed to publish WorkflowEvent {}", event.getEventType(), ex);
                });
    }
}
