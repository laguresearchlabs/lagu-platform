package com.lagu.platform.schema.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.schema.domain.OutboxEvent;
import com.lagu.platform.schema.domain.OutboxEventRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * Delivers committed schema_outbox rows to Kafka, in creation order, at-least-once.
 * Same semantics as the record-service/workflow-service relays: broker failures stop the
 * batch (retried next tick), unreadable poison rows are parked, published rows purged
 * after a retention window.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private static final String EVENTS_PACKAGE = "com.lagu.platform.events.";

    private final OutboxEventRepository outboxRepository;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxEvent> batch = outboxRepository.claimUnpublishedBatch(100);
        for (OutboxEvent row : batch) {
            Object event;
            try {
                event = objectMapper.readValue(row.getPayload(), eventClass(row.getPayloadType()));
            } catch (Exception e) {
                row.setAttempts(row.getAttempts() + 1);
                row.setPublishedAt(OffsetDateTime.now());
                log.error("Outbox row {} has unreadable payload (type={}) — parked, will not retry: {}",
                        row.getId(), row.getPayloadType(), e.getMessage());
                continue;
            }
            try {
                kafkaTemplate.send(row.getTopic(), row.getEventKey(), event)
                        .get(10, TimeUnit.SECONDS);
                row.setPublishedAt(OffsetDateTime.now());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(row, e);
                break;
            } catch (Exception e) {
                recordFailure(row, e);
                break; // keep ordering: don't let newer events pass a failed one
            }
        }
    }

    @Scheduled(cron = "${platform.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void cleanup() {
        int deleted = outboxRepository.deletePublishedBefore(OffsetDateTime.now().minusDays(7));
        if (deleted > 0) log.info("Outbox cleanup: removed {} published event(s)", deleted);
    }

    private void recordFailure(OutboxEvent row, Exception e) {
        row.setAttempts(row.getAttempts() + 1);
        log.error("Outbox publish failed (attempt {}) id={} topic={} type={}: {}",
                row.getAttempts(), row.getId(), row.getTopic(), row.getPayloadType(),
                e.getMessage());
    }

    private Class<?> eventClass(String typeName) throws ClassNotFoundException {
        if (!typeName.startsWith(EVENTS_PACKAGE)) {
            throw new IllegalStateException("Refusing to relay non-event payload type: " + typeName);
        }
        return Class.forName(typeName);
    }
}
