package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "change_set")
@Data
public class ChangeSet {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "record_id", nullable = false)
    private UUID recordId;

    @Column(name = "tenant_id", nullable = false)
    private UUID tenantId;

    @Column(name = "object_type", length = 100)
    private String objectType;

    /**
     * The workflow this change set belongs to. Server-side only.
     *
     * <p>{@code @JsonIgnore} because ChangeSetController returns this entity directly and the
     * association is LAZY: serializing it outside a session throws, and eagerly fetching it would
     * drag a whole workflow definition — states, transitions and all — into every change-set
     * response for no caller's benefit.
     *
     * <p>This was invisible until recently. {@code ChangeSetService.submit} looked the workflow up
     * in the *state* repository by workflow id, found nothing, and left this null on every change
     * set ever created — so Jackson never had a proxy to choke on. Fixing that lookup is what
     * surfaced the serialization problem underneath it.
     */
    @com.fasterxml.jackson.annotation.JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "workflow_id")
    private WorkflowDefinition workflow;

    /** The workflow id, for callers that need it — readable without initializing the proxy. */
    @com.fasterxml.jackson.annotation.JsonProperty("workflowId")
    public java.util.UUID getWorkflowId() {
        return workflow != null ? workflow.getId() : null;
    }

    @Column(nullable = false, length = 30)
    private String status = "PENDING"; // PENDING | APPROVED | REJECTED | WITHDRAWN

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "proposed_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> proposedData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "original_data", nullable = false, columnDefinition = "jsonb")
    private Map<String, Object> originalData;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "corrected_data", columnDefinition = "jsonb")
    private Map<String, Object> correctedData;

    @Column(name = "submitted_by", nullable = false)
    private UUID submittedBy;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "reviewed_by")
    private UUID reviewedBy;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Column(name = "admin_comment", columnDefinition = "TEXT")
    private String adminComment;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt = Instant.now();

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @PreUpdate
    void onUpdate() { updatedAt = Instant.now(); }
}
