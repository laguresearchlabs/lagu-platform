package com.lagu.platform.record.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.record.domain.OutboxEvent;
import com.lagu.platform.record.domain.OutboxEventRepository;
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
 * Delivers committed outbox rows to Kafka. Rows are claimed with FOR UPDATE SKIP LOCKED
 * (safe across replicas), sent synchronously in creation order, and marked published in
 * the same transaction. A failed send stops the current batch so later events for the
 * same record cannot overtake an earlier one; the batch is retried on the next tick.
 *
 * <p>Delivery is at-least-once: a crash between a successful send and the commit that
 * marks the row published re-sends that event. Consumers must tolerate duplicates (all
 * current consumers upsert by recordId or re-derive state, so replays are benign).
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
                // Poison row: we wrote this payload ourselves, so an unreadable one will never
                // succeed on retry — park it (published_at set, kept until cleanup for forensics)
                // instead of blocking every event behind it.
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
                // Broker-side failure — likely transient, retried next tick. Stop the batch so a
                // newer event for the same record cannot overtake this one.
                recordFailure(row, e);
                break;
            }
        }
        // claimed rows are managed entities — publishes/attempt counts flush on commit
    }

    /** Published rows are kept briefly for debugging/replay, then purged. */
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
