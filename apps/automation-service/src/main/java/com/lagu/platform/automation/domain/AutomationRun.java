package com.lagu.platform.automation.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "automation_run", schema = "automation")
@Getter @Setter
public class AutomationRun {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_id", nullable = false)
    private TriggerDefinition trigger;

    private UUID recordId;
    private UUID orgId;

    @Column(length = 100)
    private String eventType;

    @Column(nullable = false, length = 20)
    private String status = "PENDING";

    private String errorMessage;

    @Column(nullable = false, updatable = false)
    private Instant startedAt = Instant.now();

    private Instant completedAt;

    @OneToMany(mappedBy = "automationRun", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("executedAt ASC")
    private List<ActionRun> actionRuns;
}
