package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "approval_step_decision")
@Data
public class ApprovalStepDecision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_instance_id", nullable = false)
    private ApprovalInstance approvalInstance;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "approver_user_id", nullable = false)
    private UUID approverUserId;

    /** APPROVED | REJECTED */
    @Column(nullable = false, length = 20)
    private String decision;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "decided_at", nullable = false)
    private OffsetDateTime decidedAt;

    @PrePersist void prePersist() { decidedAt = OffsetDateTime.now(); }
}
