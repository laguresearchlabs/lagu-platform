package com.lagu.platform.vendor.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vendor_profile")
@Data
public class VendorProfile {

    /**
     * Assigned explicitly by VendorService.register() (not JPA-generated) — the same value is
     * used as record-service's tenancy/org-partition key, so it must be known before the local
     * row is saved. There used to be a separate `tenantId` column for that purpose; it was always
     * unique per vendor and never diverged from this id, so it was pure redundancy — see
     * getTenantId().
     */
    @Id
    private UUID id;

    @Column(name = "record_id", nullable = false, unique = true)
    private UUID recordId;

    @Column(name = "owner_user_id", nullable = false)
    private UUID ownerUserId;

    @Column(name = "business_name", nullable = false)
    private String businessName;

    @Column(nullable = false, length = 30)
    private String status = "DRAFT";

    /** Optimistic lock — vendor edits and admin status changes can't silently collide. */
    @Version
    @Column(nullable = false)
    private long version;

    @Column(nullable = false, length = 10)
    private String country = "IN";

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }

    public UUID getTenantId() { return id; }
}
