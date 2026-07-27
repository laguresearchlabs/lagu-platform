package com.lagu.platform.vendor.domain;

import com.lagu.platform.membership.MembershipRecord;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "vendor_member")
@Data
public class VendorMember implements MembershipRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String role = "MEMBER"; // OWNER | ADMIN | MEMBER

    @Column(nullable = false, length = 30)
    private String status = "ACTIVE"; // ACTIVE | REMOVED

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    @Column(name = "removed_by")
    private UUID removedBy;

    @Column(name = "removed_at")
    private Instant removedAt;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at")
    private Instant updatedAt;

    @Override
    public boolean isActive() {
        return "ACTIVE".equals(status);
    }
}
