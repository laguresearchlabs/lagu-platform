package com.lagu.platform.record.event;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.lagu.platform.events.PlatformTopics;
import com.lagu.platform.events.RecordEvent;
import com.lagu.platform.record.domain.OutboxEvent;
import com.lagu.platform.record.domain.OutboxEventRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.Mockito.*;

/**
 * Delivery semantics of the transactional-outbox relay: in-order publishing, park-don't-block
 * for poison rows, and stop-the-batch (retry next tick) for broker failures so a newer event
 * can never overtake a failed older one.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class OutboxRelayTest {

    @Mock OutboxEventRepository repo;
    @Mock KafkaTemplate<String, Object> kafka;

    ObjectMapper json = new ObjectMapper().findAndRegisterModules();
    OutboxRelay relay;

    @BeforeEach
    void setUp() {
        relay = new OutboxRelay(repo, kafka, json);
    }

    private OutboxEvent row(String key) throws Exception {
        RecordEvent event = RecordEvent.builder()
                .eventType("CREATED")
                .recordId(UUID.randomUUID())
                .orgId(UUID.randomUUID())
                .objectType("VENUE")
                .occurredAt(Instant.now())
                .build();
        OutboxEvent row = new OutboxEvent();
        row.setId(UUID.randomUUID());
        row.setTopic(PlatformTopics.RECORD_EVENTS);
        row.setEventKey(key);
        row.setPayloadType(RecordEvent.class.getName());
        row.setPayload(json.writeValueAsString(event));
        return row;
    }

    private static CompletableFuture<SendResult<String, Object>> ok() {
        return CompletableFuture.completedFuture(null);
    }

    private static CompletableFuture<SendResult<String, Object>> broken() {
        return CompletableFuture.failedFuture(new RuntimeException("broker unavailable"));
    }

    @Test
    void publishesClaimedRowsInOrderAndMarksThem() throws Exception {
        OutboxEvent first = row("org:rec-1");
        OutboxEvent second = row("org:rec-2");
        when(repo.claimUnpublishedBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(any(), any(), any())).thenReturn(ok());

        relay.relay();

        var order = inOrder(kafka);
        order.verify(kafka).send(PlatformTopics.RECORD_EVENTS, "org:rec-1", deserialized(first));
        order.verify(kafka).send(PlatformTopics.RECORD_EVENTS, "org:rec-2", deserialized(second));
        assertThat(first.getPublishedAt()).isNotNull();
        assertThat(second.getPublishedAt()).isNotNull();
    }

    @Test
    void brokerFailureStopsBatchWithoutMarkingOrSkipping() throws Exception {
        OutboxEvent first = row("org:rec-1");
        OutboxEvent second = row("org:rec-2");
        when(repo.claimUnpublishedBatch(anyInt())).thenReturn(List.of(first, second));
        when(kafka.send(any(), any(), any())).thenReturn(broken());

        relay.relay();

        // first row failed → attempt counted, not published; second row never attempted
        assertThat(first.getPublishedAt()).isNull();
        assertThat(first.getAttempts()).isEqualTo(1);
        assertThat(second.getPublishedAt()).isNull();
        assertThat(second.getAttempts()).isZero();
        verify(kafka, times(1)).send(any(), any(), any());
    }

    @Test
    void poisonRowIsParkedAndDoesNotBlockTheStream() throws Exception {
        OutboxEvent poison = row("org:rec-1");
        poison.setPayloadType("java.lang.ProcessBuilder"); // outside the events package → refused
        OutboxEvent healthy = row("org:rec-2");
        when(repo.claimUnpublishedBatch(anyInt())).thenReturn(List.of(poison, healthy));
        when(kafka.send(any(), any(), any())).thenReturn(ok());

        relay.relay();

        // poison parked (won't retry), healthy row still delivered
        assertThat(poison.getPublishedAt()).isNotNull();
        assertThat(poison.getAttempts()).isEqualTo(1);
        assertThat(healthy.getPublishedAt()).isNotNull();
        verify(kafka, times(1)).send(eq(PlatformTopics.RECORD_EVENTS), eq("org:rec-2"), any());
    }

    private RecordEvent deserialized(OutboxEvent row) throws Exception {
        return json.readValue(row.getPayload(), RecordEvent.class);
    }
}
