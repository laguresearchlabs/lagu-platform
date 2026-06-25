package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "transition_history")
@Data
public class TransitionHistory {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "workflow_id", nullable = false)
    private UUID workflowId;

    @Column(name = "from_state", length = 50)
    private String fromState;

    @Column(name = "to_state", nullable = false, length = 50)
    private String toState;

    @Column(name = "trigger_name", length = 100)
    private String triggerName;

    @Column(columnDefinition = "TEXT")
    private String comment;

    @Column(name = "transitioned_by")
    private UUID transitionedBy;

    @Column(name = "transitioned_at", nullable = false)
    private OffsetDateTime transitionedAt;

    @PrePersist void prePersist() { transitionedAt = OffsetDateTime.now(); }
}
