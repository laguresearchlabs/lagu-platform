package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.util.UUID;

@Entity
@Table(name = "approval_step")
@Data
public class ApprovalStep {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_def_id", nullable = false)
    private ApprovalDefinition approvalDefinition;

    @Column(name = "step_order", nullable = false)
    private int stepOrder;

    @Column(name = "step_label", nullable = false, length = 200)
    private String stepLabel;

    @Column(name = "approver_role", nullable = false, length = 100)
    private String approverRole;

    @Column(name = "timeout_hours")
    private Integer timeoutHours;

    @Column(name = "escalate_to_role", length = 100)
    private String escalateToRole;
}
