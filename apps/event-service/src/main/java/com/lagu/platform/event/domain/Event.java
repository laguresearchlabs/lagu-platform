package com.lagu.platform.event.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event")
@Data
public class Event {

    /**
     * Assigned explicitly by EventService.create() (not JPA-generated) — the same value is used
     * as record-service's tenancy/org-partition key, so it must be known before the local row is
     * saved. There used to be a separate `tenantId` column for that purpose; it was always unique
     * per event and never diverged from this id, so it was pure redundancy — see getTenantId().
     */
    @Id
    private UUID id;

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

    /**
     * Event.id doubles as the org-partition key record-service's generic engine expects — kept
     * as a separate accessor (rather than inlining `getId()` at every call site) so every
     * existing `event.getTenantId()` caller (record-service tenancy calls, EventMember/
     * EventJoinRequest's own tenant_id columns, EventMembershipPermissionEvaluator) keeps working
     * unchanged.
     */
    public UUID getTenantId() {
        return id;
    }
}
