package com.lagu.platform.automation.domain;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "action_definition", schema = "automation")
@Getter @Setter
public class ActionDefinition {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "trigger_id", nullable = false)
    private TriggerDefinition trigger;

    @Column(nullable = false, length = 50)
    private String actionType;

    @Column(nullable = false)
    private int executionOrder = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config;

    @Column(nullable = false)
    private boolean continueOnFailure = true;

    @Column(nullable = false)
    private boolean isActive = true;
}
