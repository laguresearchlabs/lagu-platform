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

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

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

    /**
     * The average of this listing's VERIFIED reviews, or null when it has none. Null rather than
     * zero on purpose: "unrated" and "rated badly" are different claims and render differently.
     * The reviews themselves live in booking-service; only the aggregate is here, because the
     * snapshot is what becomes a ListingEvent and therefore what search-service indexes.
     */
    @Column(name = "rating_average", precision = 2, scale = 1)
    private BigDecimal ratingAverage;

    /** How many verified reviews the average was computed from. */
    @Column(name = "review_count", nullable = false)
    private int reviewCount = 0;

    @Column(name = "published_at", nullable = false)
    private Instant publishedAt = Instant.now();

    // Real optimistic locking — this used to be a plain hand-incremented column
    // ("snap.setVersion(snap.getVersion() + 1)"), which does nothing to prevent two concurrent
    // read-modify-write cycles (e.g. a workflow transition racing a manual re-publish) from
    // losing one's update, or two inserts racing on the record_id unique constraint. @Version
    // makes Hibernate check-and-increment atomically and throw OptimisticLockException on a
    // genuine conflict instead of silently overwriting.
    @Version
    @Column(nullable = false)
    private long version = 0;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
