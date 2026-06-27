package com.lagu.platform.metadata.domain;

import jakarta.persistence.*;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "relationship_definition", schema = "metadata")
@Data
@NoArgsConstructor
public class RelationshipDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "org_id")
    private UUID orgId;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String label;

    @Column(name = "source_object_type", nullable = false, length = 100)
    private String sourceObjectType;

    @Column(name = "target_object_type", nullable = false, length = 100)
    private String targetObjectType;

    @Column(name = "relationship_type", nullable = false, length = 50)
    private String relationshipType;  // ONE_TO_ONE, ONE_TO_MANY, MANY_TO_MANY, PARENT_CHILD

    @Column(name = "is_required", nullable = false)
    private boolean required = false;

    @Column(name = "cascade_delete", nullable = false)
    private boolean cascadeDelete = false;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
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
