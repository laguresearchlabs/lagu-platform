package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "approval_instance")
@Data
public class ApprovalInstance {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_def_id", nullable = false)
    private ApprovalDefinition approvalDefinition;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "transition_id", nullable = false)
    private WorkflowTransition transition;

    /** PENDING | APPROVED | REJECTED */
    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    @Column(name = "current_step", nullable = false)
    private int currentStep = 1;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @OneToMany(mappedBy = "approvalInstance", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC, decidedAt ASC")
    private List<ApprovalStepDecision> decisions = new ArrayList<>();

    @PrePersist void prePersist() { createdAt = OffsetDateTime.now(); }
}
