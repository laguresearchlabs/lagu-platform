package com.lagu.platform.record.domain;

import com.lagu.platform.common.converter.JsonbConverter;
import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "record",
       indexes = {
           @Index(name = "idx_record_org_type",   columnList = "org_id, object_type"),
           @Index(name = "idx_record_org_status",  columnList = "org_id, object_type, status"),
           @Index(name = "idx_record_created_at",  columnList = "org_id, object_type, created_at")
       })
@Data
@NoArgsConstructor
public class Record {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "object_type", nullable = false, length = 100)
    private String objectType;

    @Column(nullable = false, length = 50)
    private String status = "DRAFT";

    @Convert(converter = JsonbConverter.class)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> data = new HashMap<>();

    @Column(name = "created_by")
    private UUID createdBy;

    @Column(name = "updated_by")
    private UUID updatedBy;

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
