package com.lagu.platform.automation.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "action_run", schema = "automation")
@Getter @Setter
public class ActionRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "automation_run_id", nullable = false)
    private AutomationRun automationRun;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "action_id", nullable = false)
    private ActionDefinition action;

    @Column(length = 50)
    private String actionType;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant executedAt = Instant.now();
}
