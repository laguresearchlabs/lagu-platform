package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A pending workflow event, written in the same transaction as the state change that
 * produced it and published to Kafka asynchronously by {@code OutboxRelay}. Mirrors
 * record-service's outbox (candidate for extraction into libs/common once a third
 * service needs it).
 */
@Entity
@Table(name = "workflow_outbox")
@Data
@NoArgsConstructor
public class OutboxEvent {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String topic;

    @Column(name = "event_key", nullable = false, length = 200)
    private String eventKey;

    /** Fully-qualified class name from com.lagu.platform.events — restored before publishing
     *  so the Kafka wire format (JsonSerializer type headers) is identical to a direct send. */
    @Column(name = "payload_type", nullable = false, length = 200)
    private String payloadType;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String payload;

    @Column(nullable = false)
    private int attempts;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
    }
}
