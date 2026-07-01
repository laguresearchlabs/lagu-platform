package com.lagu.platform.metadata.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "tier_configuration")
@Data
@NoArgsConstructor
public class TierConfiguration {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tier_name", nullable = false, length = 30)
    private String tierName;

    @Column(name = "object_type", length = 100)
    private String objectType;

    @Column(name = "commission_rate", nullable = false, precision = 5, scale = 2)
    private BigDecimal commissionRate = new BigDecimal("20.00");

    /** Null means unlimited. */
    @Column(name = "max_active_bookings")
    private Integer maxActiveBookings;

    @Column(name = "search_boost_factor", nullable = false, precision = 4, scale = 2)
    private BigDecimal searchBoostFactor = BigDecimal.ONE;

    @Column(name = "response_sla_hours")
    private int responseSlaHours = 48;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> features;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
