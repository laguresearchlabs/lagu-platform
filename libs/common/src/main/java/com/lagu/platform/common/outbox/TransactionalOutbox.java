package com.lagu.platform.common.outbox;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;

/**
 * Staging API for the transactional outbox. Call {@link #stage} inside the transaction
 * that makes the corresponding state change, so the event and the change commit or roll
 * back together; {@link OutboxRelay} delivers committed rows to Kafka.
 */
@Component
@ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
@RequiredArgsConstructor
public class TransactionalOutbox {

    private final OutboxStore store;
    private final ObjectMapper objectMapper;

    public void stage(String topic, String eventKey, Object event) {
        try {
            store.insert(topic, eventKey, event.getClass().getName(), objectMapper.writeValueAsString(event));
        } catch (JsonProcessingException e) {
            // Must propagate: failing to stage the event has to roll back the caller's change,
            // otherwise we are back to silent DB/event divergence.
            throw new IllegalStateException("Could not serialize " + event.getClass().getSimpleName()
                    + " for outbox", e);
        }
    }
}
