package com.lagu.platform.workflow.domain;

import com.lagu.platform.common.converter.JsonbConverter;
import com.lagu.platform.common.converter.JsonbListConverter;
import jakarta.persistence.*;
import lombok.Data;

import java.util.List;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "workflow_transition")
@Data
public class WorkflowTransition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private WorkflowDefinition workflow;

    @Column(name = "from_state", nullable = false, length = 50)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 50)
    private String toState;

    @Column(name = "trigger_name", nullable = false, length = 100)
    private String triggerName;

    @Column(name = "trigger_label", length = 200)
    private String triggerLabel;

    @Convert(converter = JsonbListConverter.class)
    @Column(name = "allowed_roles", columnDefinition = "jsonb")
    private List<String> allowedRoles;

    @Column(name = "requires_approval", nullable = false)
    private boolean requiresApproval = false;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "approval_def_id")
    private ApprovalDefinition approvalDefinition;

    @Convert(converter = JsonbConverter.class)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> conditions;
}
