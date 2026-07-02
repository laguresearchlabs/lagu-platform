package com.lagu.platform.workflow.event;

import com.lagu.platform.events.PlatformTopics;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

/** Actually sends WorkflowEvents to Kafka, only once the originating DB transaction has committed. */
@Component
@RequiredArgsConstructor
@Slf4j
public class WorkflowKafkaPublisherListener {

    private final KafkaTemplate<String, Object> kafka;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onCommit(OutboundWorkflowEvent outbound) {
        kafka.send(PlatformTopics.WORKFLOW_EVENTS, outbound.getPartitionKey(), outbound.getEvent())
                .whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("Failed to publish WorkflowEvent {}", outbound.getEvent().getEventType(), ex);
                    }
                });
    }
}
