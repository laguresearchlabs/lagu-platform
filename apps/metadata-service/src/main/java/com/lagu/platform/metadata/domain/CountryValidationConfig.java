package com.lagu.platform.metadata.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "country_validation_config")
@Data
@NoArgsConstructor
public class CountryValidationConfig {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, unique = true, length = 5)
    private String country;

    /**
     * Keyed by field name (pan, gstin, ifsc, phone…).
     * Each value: { "pattern": "...", "label": "..." }
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> rules;

    @Column(nullable = false, length = 5)
    private String currency = "INR";

    @Column(name = "tax_label", nullable = false, length = 20)
    private String taxLabel = "GST";

    @Column(name = "dial_code", length = 10)
    private String dialCode;

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
