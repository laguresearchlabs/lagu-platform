package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A pending schema event, written in the same transaction as the schema change that
 * produced it and published to Kafka asynchronously by {@code OutboxRelay}. Third copy of
 * this pattern (record-service, workflow-service) — extract to libs/common deliberately,
 * not by default: every service component-scans com.lagu.platform.common, so a shared
 * relay would activate in services that have no outbox table.
 */
@Entity
@Table(name = "schema_outbox")
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
