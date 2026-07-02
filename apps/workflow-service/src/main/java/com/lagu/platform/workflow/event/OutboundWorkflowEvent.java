package com.lagu.platform.workflow.event;

import com.lagu.platform.events.WorkflowEvent;
import org.springframework.context.ApplicationEvent;

/**
 * Spring application event wrapping a {@link WorkflowEvent} destined for Kafka. Published from
 * within the DB transaction that produced it; actually sent to Kafka only after that transaction
 * commits (see {@link WorkflowKafkaPublisherListener}), so a rollback never leaves a Kafka
 * consumer reacting to a state change that was never actually persisted.
 */
public class OutboundWorkflowEvent extends ApplicationEvent {

    private final String partitionKey;
    private final WorkflowEvent event;

    public OutboundWorkflowEvent(Object source, String partitionKey, WorkflowEvent event) {
        super(source);
        this.partitionKey = partitionKey;
        this.event = event;
    }

    public String getPartitionKey() {
        return partitionKey;
    }

    public WorkflowEvent getEvent() {
        return event;
    }
}
