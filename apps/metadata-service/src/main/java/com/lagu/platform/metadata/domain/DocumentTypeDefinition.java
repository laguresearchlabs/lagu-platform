package com.lagu.platform.metadata.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "document_type_definition")
@Data
@NoArgsConstructor
public class DocumentTypeDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(name = "object_type", length = 100)
    private String objectType;

    @Column(nullable = false, length = 100)
    private String code;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Column(name = "is_required")
    private boolean required = false;

    @Column(name = "expiry_tracked")
    private boolean expiryTracked = false;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "allowed_mime_types", columnDefinition = "jsonb")
    private List<String> allowedMimeTypes;

    @Column(name = "max_size_mb")
    private int maxSizeMb = 5;

    @Column(name = "is_active")
    private boolean active = true;

    @Column(name = "display_order")
    private int displayOrder = 0;

    @Column(name = "created_at")
    private OffsetDateTime createdAt;

    @Column(name = "updated_at")
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() { createdAt = updatedAt = OffsetDateTime.now(); }

    @PreUpdate
    void onUpdate() { updatedAt = OffsetDateTime.now(); }
}
