package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "tier_eligibility_rule")
@Data
@NoArgsConstructor
public class TierEligibilityRule {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "listing_type", nullable = false, length = 100)
    private String listingType;

    @Column(nullable = false, length = 30)
    private String tier;

    @Column(name = "rule_type", nullable = false, length = 30)
    private String ruleType;   // DOCUMENT_VERIFIED | FIELD_CONDITION | MIN_BOOKINGS

    @Column(name = "document_code", length = 100)
    private String documentCode;

    @Column(name = "field_path", length = 200)
    private String fieldPath;

    @Column(length = 10)
    private String operator;   // EQ | NEQ | GTE | LTE | IN | NOT_NULL

    @Column(length = 200)
    private String value;

    @Column(name = "min_count")
    private Integer minCount;

    @Column(name = "force_overridable")
    private boolean forceOverridable = true;

    @Column(name = "display_name", nullable = false, length = 200)
    private String displayName;

    @Column(length = 500)
    private String description;

    @Column(name = "display_order")
    private int displayOrder = 0;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate  void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
