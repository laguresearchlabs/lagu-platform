package com.lagu.platform.schema.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "field_definition",
       uniqueConstraints = @UniqueConstraint(columnNames = {"name", "org_id"}))
@Data
@NoArgsConstructor
public class FieldDefinition {

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
    @Column(name = "field_type", nullable = false, length = 50)
    private FieldType fieldType;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "enum_values", columnDefinition = "jsonb")
    private List<String> enumValues;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "item_schema", columnDefinition = "jsonb")
    private List<Map<String, Object>> itemSchema;

    @Column(name = "reference_type", length = 100)
    private String referenceType;

    @Column(name = "is_required")
    private boolean required = false;

    @Column(name = "is_unique")
    private boolean unique = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "validation_rules", columnDefinition = "jsonb")
    private Map<String, Object> validationRules;

    @Column(name = "default_value", columnDefinition = "TEXT")
    private String defaultValue;

    @Column(name = "is_searchable")
    private boolean searchable = false;

    @Column(name = "is_filterable")
    private boolean filterable = false;

    @Column(name = "is_sortable")
    private boolean sortable = false;

    @Column(name = "is_facetable")
    private boolean facetable = false;

    @Column(name = "is_promoted")
    private boolean promoted = false;

    @Column(name = "is_range_filterable")
    private boolean rangeFilterable = false;

    @Column(name = "is_array_manageable")
    private boolean arrayManageable = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> config;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }
    @PreUpdate  void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
