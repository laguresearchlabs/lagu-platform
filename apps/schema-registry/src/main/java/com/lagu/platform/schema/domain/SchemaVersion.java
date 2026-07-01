package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "schema_version",
       uniqueConstraints = @UniqueConstraint(columnNames = {"listing_type", "version"}))
@Data
@NoArgsConstructor
public class SchemaVersion {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_type", nullable = false, length = 100)
    private String listingType;

    @Column(nullable = false)
    private int version;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "schema_snapshot", columnDefinition = "jsonb")
    private Map<String, Object> schemaSnapshot;

    @Column(name = "change_classification", length = 20)
    private String changeClassification = "SAFE";   // SAFE | SOFT_BREAKING | HARD_BREAKING

    @Column(name = "change_summary", columnDefinition = "TEXT")
    private String changeSummary;

    @Column(name = "published_by", length = 255)
    private String publishedBy;

    @Column(name = "published_at")
    private OffsetDateTime publishedAt;

    @PrePersist void onCreate() { if (publishedAt == null) publishedAt = OffsetDateTime.now(); }
}
