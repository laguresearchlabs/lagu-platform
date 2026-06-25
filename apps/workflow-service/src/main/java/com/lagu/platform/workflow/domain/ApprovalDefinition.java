package com.lagu.platform.workflow.domain;

import jakarta.persistence.*;
import lombok.Data;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "approval_definition")
@Data
public class ApprovalDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(nullable = false, length = 200)
    private String label;

    /** SEQUENTIAL | PARALLEL | ANY_ONE */
    @Column(name = "approval_type", nullable = false, length = 20)
    private String approvalType = "SEQUENTIAL";

    @Column(name = "timeout_hours")
    private Integer timeoutHours;

    @Column(name = "is_active", nullable = false)
    private boolean active = true;

    @OneToMany(mappedBy = "approvalDefinition", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("stepOrder ASC")
    private List<ApprovalStep> steps = new ArrayList<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @PrePersist void prePersist() { createdAt = OffsetDateTime.now(); }
}
