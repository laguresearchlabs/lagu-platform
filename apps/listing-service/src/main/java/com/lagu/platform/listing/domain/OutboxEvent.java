package com.lagu.platform.listing.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * A pending listing event, written in the same transaction as the snapshot change that
 * produced it and published to Kafka asynchronously by {@code OutboxRelay}. Fourth copy of
 * this pattern — the libs/common extraction (with @ConditionalOnProperty activation, since
 * every service scans com.lagu.platform.common) is now clearly warranted as a follow-up.
 */
@Entity
@Table(name = "listing_outbox")
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
