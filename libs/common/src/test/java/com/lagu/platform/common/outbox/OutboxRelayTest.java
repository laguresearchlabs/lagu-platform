package com.lagu.platform.common.outbox;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.support.SendResult;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Delivery semantics of the transactional-outbox relay: in-order publishing, park-don't-block
 * for poison rows, and stop-the-batch (retry next tick) for broker failures so a newer event
 * can never overtake a failed older one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest {

    @Mock OutboxStore store;
    @Mock KafkaTemplate<String, Object> kafka;

    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(store, kafka, json);
    }

    private OutboxRow row(String key) throws Exception {
        return row(key, RecordEvent.class.getName());
    }

    private OutboxRow row(String key, String payloadType) throws Exception {
        RecordEvent event = RecordEvent.builder()
                .eventType("CREATED")
                .recordId(UUID.randomUUID())
                .tenantId(UUID.randomUUID())
                .objectType("VENUE")
                .occurredAt(Instant.now())
                .build();
        return new OutboxRow(UUID.randomUUID(), PlatformTopics.RECORD_EVENTS, key,
                payloadType, json.writeValueAsString(event), 0);
    }

    private static CompletableFuture<SendResult<String, Object>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, Object>> broken() {
        return CompletableFuture.failedFuture(new RuntimeException("broker unavailable"));
    }

    @Test
    void publishesClaimedRowsInOrderAndMarksThem() throws Exception {
        OutboxRow first = row("org:rec-1");
        OutboxRow second = row("org:rec-2");
        when(store.claimUnpublishedBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(any(), any(), any())).thenReturn(ok());

        relay.relay();

        var order = inOrder(kafka, store);
        order.verify(kafka).send(PlatformTopics.RECORD_EVENTS, "org:rec-1", deserialized(first));
        order.verify(store).markPublished(first.id());
        order.verify(kafka).send(PlatformTopics.RECORD_EVENTS, "org:rec-2", deserialized(second));
        order.verify(store).markPublished(second.id());
        verify(store, never()).park(any());
        verify(store, never()).recordFailure(any());
    }

    @Test
    void brokerFailureStopsBatchWithoutMarkingOrSkipping() throws Exception {
        OutboxRow first = row("org:rec-1");
        OutboxRow second = row("org:rec-2");
        when(store.claimUnpublishedBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(any(), any(), any())).thenReturn(broken());

        relay.relay();

        // first row failed → attempt counted, not published; second row never attempted
        verify(store).recordFailure(first.id());
        verify(store, never()).markPublished(any());
        verify(store, never()).recordFailure(second.id());
        verify(kafka, times(1)).send(any(), any(), any());
    }

    @Test
    void poisonRowIsParkedAndDoesNotBlockTheStream() throws Exception {
        // payload type outside the events package → refused
        OutboxRow poison = row("org:rec-1", "java.lang.ProcessBuilder");
        OutboxRow healthy = row("org:rec-2");
        when(store.claimUnpublishedBatch(anyInt())).thenReturn(List.of(poison, healthy));
        when(kafka.send(any(), any(), any())).thenReturn(ok());

        relay.relay();

        // poison parked (won't retry), healthy row still delivered
        verify(store).park(poison.id());
        verify(store).markPublished(healthy.id());
        verify(kafka, times(1)).send(eq(PlatformTopics.RECORD_EVENTS), eq("org:rec-2"), any());
    }

    private RecordEvent deserialized(OutboxRow row) throws Exception {
        return json.readValue(row.payload(), RecordEvent.class);
    }
}
