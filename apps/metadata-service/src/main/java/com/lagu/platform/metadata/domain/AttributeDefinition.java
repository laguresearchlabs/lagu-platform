package com.lagu.platform.metadata.domain;

import com.lagu.platform.common.converter.JsonbConverter;
import com.lagu.platform.common.converter.JsonbListConverter;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "attribute_definition",
       uniqueConstraints = @UniqueConstraint(columnNames = {"name", "org_id"}))
@Data
@NoArgsConstructor
public class AttributeDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(name = "attribute_type", nullable = false, length = 50)
    private AttributeType attributeType;

    @Column(name = "is_required")
    private boolean required;

    @Column(name = "is_searchable")
    private boolean searchable;

    @Column(name = "is_filterable")
    private boolean filterable;

    @Column(name = "is_sortable")
    private boolean sortable;

    @Column(name = "is_facetable")
    private boolean facetable;

    @Column(name = "is_unique")
    private boolean unique;

    @Column(name = "default_value")
    private String defaultValue;

    @Convert(converter = JsonbConverter.class)
    @Column(name = "validation_rules", columnDefinition = "jsonb")
    private Map<String, Object> validationRules;

    @Convert(converter = JsonbListConverter.class)
    @Column(name = "enum_values", columnDefinition = "jsonb")
    private List<String> enumValues;

    @Convert(converter = JsonbConverter.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = updatedAt = OffsetDateTime.now();
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
