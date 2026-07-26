package com.lagu.platform.event.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "event_member")
@Data
public class EventMember {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    /** = Event.orgId */
    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(nullable = false, length = 30)
    private String role = "INVITEE"; // ADMIN | MAINTAINER | INVITEE

    @Column(nullable = false, length = 30)
    private String status = "ACCEPTED"; // INVITED | ACCEPTED | DECLINED

    @Column(name = "guest_note", length = 500)
    private String guestNote;

    @Column(nullable = false)
    private boolean muted = false;

    @Column(name = "invited_by")
    private UUID invitedBy;

    @Column(name = "joined_at", nullable = false)
    private Instant joinedAt = Instant.now();

    public boolean canManage() {
        return "ADMIN".equals(role) || "MAINTAINER".equals(role);
    }
}
