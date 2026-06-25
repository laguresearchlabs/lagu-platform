package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.UUID;

@Entity
@Table(name = "record_workflow_state")
@Data
public class RecordWorkflowState {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "record_id", nullable = false, unique = true)
    private UUID recordId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "object_type", nullable = false, length = 100)
    private String objectType;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id", nullable = false)
    private WorkflowDefinition workflow;

    @Column(name = "current_state", nullable = false, length = 50)
    private String currentState;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist @PreUpdate void touch() { updatedAt = OffsetDateTime.now(); }
}
