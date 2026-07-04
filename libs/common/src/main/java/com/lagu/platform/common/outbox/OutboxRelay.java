package com.lagu.platform.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
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
 * same key cannot overtake an earlier one; the batch is retried on the next tick.
 *
 * <p>Delivery is at-least-once: a crash between a successful send and the commit that
 * marks the row published re-sends that event. Consumers must tolerate duplicates (all
 * current consumers upsert by recordId or re-derive state, so replays are benign).
 *
 * <p>Inert unless the host service sets {@code platform.outbox.enabled=true} — every
 * service component-scans this package, including services with no outbox table. Enabling
 * services must also have {@code @EnableScheduling}.
 */
@Component
@ConditionalOnProperty(name = "platform.outbox.enabled", havingValue = "true")
@RequiredArgsConstructor
@Slf4j
public class OutboxRelay {

    private static final String EVENTS_PACKAGE = "com.lagu.platform.events.";

    private final OutboxStore store;
    private final KafkaTemplate<String, Object> kafkaTemplate;
    private final ObjectMapper objectMapper;

    @Scheduled(fixedDelayString = "${platform.outbox.poll-interval-ms:1000}")
    @Transactional
    public void relay() {
        List<OutboxRow> batch = store.claimUnpublishedBatch(100);
        for (OutboxRow row : batch) {
            Object event;
            try {
                // Payload type restored so the Kafka wire format (JsonSerializer type
                // headers) is identical to a direct send.
                event = objectMapper.readValue(row.payload(), eventClass(row.payloadType()));
            } catch (Exception e) {
                // Poison row: we wrote this payload ourselves, so an unreadable one will never
                // succeed on retry — park it instead of blocking every event behind it.
                store.park(row.id());
                log.error("Outbox row {} has unreadable payload (type={}) — parked, will not retry: {}",
                        row.id(), row.payloadType(), e.getMessage());
                continue;
            }
            try {
                kafkaTemplate.send(row.topic(), row.eventKey(), event)
                        .get(10, TimeUnit.SECONDS);
                store.markPublished(row.id());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                recordFailure(row, e);
                break;
            } catch (Exception e) {
                // Broker-side failure — likely transient, retried next tick. Stop the batch so a
                // newer event for the same key cannot overtake this one.
                recordFailure(row, e);
                break;
            }
        }
    }

    /** Published rows are kept briefly for debugging/replay, then purged. */
    @Scheduled(cron = "${platform.outbox.cleanup-cron:0 0 3 * * *}")
    @Transactional
    public void cleanup() {
        int deleted = store.deletePublishedBefore(OffsetDateTime.now().minusDays(7));
        if (deleted > 0) log.info("Outbox cleanup: removed {} published event(s)", deleted);
    }

    private void recordFailure(OutboxRow row, Exception e) {
        store.recordFailure(row.id());
        log.error("Outbox publish failed (attempt {}) id={} topic={} type={}: {}",
                row.attempts() + 1, row.id(), row.topic(), row.payloadType(), e.getMessage());
    }

    private Class<?> eventClass(String typeName) throws ClassNotFoundException {
        if (!typeName.startsWith(EVENTS_PACKAGE)) {
            throw new IllegalStateException("Refusing to relay non-event payload type: " + typeName);
        }
        return Class.forName(typeName);
    }
}
