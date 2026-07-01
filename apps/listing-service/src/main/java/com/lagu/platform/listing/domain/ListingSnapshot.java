package com.lagu.platform.listing.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "listing_snapshot")
@Data
public class ListingSnapshot {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "record_id", nullable = false, unique = true)
    private UUID recordId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "object_type", nullable = false, length = 100)
    private String objectType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> data;

    @Column(nullable = false, length = 30)
    private String status = "PUBLISHED";

    @Column(name = "verification_tier", nullable = false, length = 20)
    private String verificationTier = "NONE";

    @Column(name = "search_boost", nullable = false, precision = 5, scale = 2)
    private BigDecimal searchBoost = BigDecimal.ONE;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt = Instant.now();

    @Column(nullable = false)
    private long version = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
