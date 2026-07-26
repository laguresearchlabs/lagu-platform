package com.lagu.platform.event.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event")
@Data
public class Event {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** Throwaway, internal-only partition key minted at creation — never propagated to IAM. */
    @Column(name = "org_id", nullable = false, unique = true)
    private UUID orgId;

    @Column(name = "record_id", nullable = false, unique = true)
    private UUID recordId;

    @Column(name = "object_type", nullable = false, length = 60)
    private String objectType;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(nullable = false, length = 30)
    private String status = "PLANNING";

    /** Optimistic lock — concurrent edits/transitions conflict instead of silently colliding. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
