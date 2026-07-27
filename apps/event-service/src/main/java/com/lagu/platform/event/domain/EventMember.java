package com.lagu.platform.event.domain;

import com.lagu.platform.membership.MembershipRecord;
import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_member")
@Data
public class EventMember implements MembershipRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** = Event.tenantId */
    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String role = "INVITEE"; // ADMIN | MAINTAINER | INVITEE

    @Column(nullable = false, length = 30)
    private String status = "ACCEPTED"; // INVITED | ACCEPTED | DECLINED | REMOVED

    @Column(name = "guest_note", length = 500)
    private String guestNote;

    @Column(nullable = false)
    private boolean muted = false;

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

    public boolean canManage() {
        return "ADMIN".equals(role) || "MAINTAINER".equals(role);
    }

    @Override
    public boolean isActive() {
        return "ACCEPTED".equals(status);
    }
}
