package com.lagu.platform.automation.domain;

import com.fasterxml.jackson.annotation.JsonIgnore;
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

    // @JsonIgnore: TriggerController returns this entity directly (no DTO layer), and without
    // this, Jackson tries to serialize this lazy proxy after the transaction that loaded it has
    // already closed (open-in-view is intentionally false) — a guaranteed
    // LazyInitializationException on every response containing an action. The client already
    // knows the owning trigger's id from the request path; it doesn't need it embedded back.
    @JsonIgnore
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
