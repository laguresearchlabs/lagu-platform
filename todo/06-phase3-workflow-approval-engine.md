# Phase 3 — Workflow Engine & Approval Engine

## New Service: workflow-service

This is Phase 3's deliverable: a dedicated `workflow-service` that owns:

- Workflow definitions (states + transitions)
- State machine execution
- Approval step definitions and execution
- Timeout / escalation rules

The record-service **delegates** all status changes to workflow-service via Kafka.
The record-service never transitions its own status — it fires an event and waits.

---

## Concept: Metadata-Driven State Machine

```
Admin defines:

  WorkflowDefinition
      name: "VENDOR_APPROVAL"
      objectType: "PHOTOGRAPHER"    ← applies to this object type
      states:
        - DRAFT
        - SUBMITTED
        - UNDER_REVIEW
        - APPROVED
        - REJECTED
        - SUSPENDED
        - ARCHIVED
      transitions:
        - from: DRAFT       → to: SUBMITTED     trigger: SUBMIT       roles: [ORG_OWNER, ORG_MANAGER]
        - from: SUBMITTED   → to: UNDER_REVIEW  trigger: START_REVIEW roles: [CONFIG_ADMIN]
        - from: UNDER_REVIEW → to: APPROVED     trigger: APPROVE      roles: [CONFIG_ADMIN], requiresApproval: true
        - from: UNDER_REVIEW → to: REJECTED     trigger: REJECT       roles: [CONFIG_ADMIN]
        - from: APPROVED    → to: SUSPENDED     trigger: SUSPEND      roles: [PLATFORM_ADMIN]
        - from: APPROVED    → to: ARCHIVED      trigger: ARCHIVE      roles: [CONFIG_ADMIN]
```

No code change to add a new workflow. Admin configures it.

---

## Database Schema

### Flyway: `resources/db/migration/V1__workflow_schema.sql`

```sql
CREATE TABLE workflow.workflow_definition (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID,                       -- NULL = platform-level
    name            VARCHAR(100) NOT NULL,
    label           VARCHAR(200) NOT NULL,
    object_type     VARCHAR(100) NOT NULL,       -- e.g. "VENUE", "PHOTOGRAPHER"
    initial_status  VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_workflow_object_org UNIQUE (object_type, org_id)
);

CREATE TABLE workflow.workflow_state (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id     UUID        NOT NULL REFERENCES workflow.workflow_definition(id) ON DELETE CASCADE,
    name            VARCHAR(50)  NOT NULL,       -- DRAFT, SUBMITTED, etc.
    label           VARCHAR(100) NOT NULL,
    description     TEXT,
    is_terminal     BOOLEAN      NOT NULL DEFAULT false,  -- APPROVED, REJECTED, ARCHIVED
    display_order   INT          NOT NULL DEFAULT 0,
    color           VARCHAR(20),                -- UI hint: "green", "#4CAF50"
    CONSTRAINT uq_state_name_workflow UNIQUE (workflow_id, name)
);

CREATE TABLE workflow.workflow_transition (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id         UUID        NOT NULL REFERENCES workflow.workflow_definition(id) ON DELETE CASCADE,
    from_state          VARCHAR(50) NOT NULL,
    to_state            VARCHAR(50) NOT NULL,
    trigger_name        VARCHAR(100) NOT NULL,   -- action label, e.g. "SUBMIT", "APPROVE"
    trigger_label       VARCHAR(200),            -- human-readable button label
    allowed_roles       JSONB,                   -- ["CONFIG_ADMIN", "ORG_MANAGER"]
    requires_approval   BOOLEAN     NOT NULL DEFAULT false,
    approval_def_id     UUID        REFERENCES workflow.approval_definition(id),
    conditions          JSONB,                   -- future: field-based conditions
    CONSTRAINT uq_transition UNIQUE (workflow_id, from_state, trigger_name)
);

-- ── Approval Engine ─────────────────────────────────────────────────────────

CREATE TABLE workflow.approval_definition (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    name            VARCHAR(100) NOT NULL,
    label           VARCHAR(200) NOT NULL,
    approval_type   VARCHAR(20)  NOT NULL DEFAULT 'SEQUENTIAL',  -- SEQUENTIAL, PARALLEL, ANY_ONE
    timeout_hours   INT,                 -- escalate after N hours; NULL = no timeout
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE workflow.approval_step (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_def_id UUID        NOT NULL REFERENCES workflow.approval_definition(id) ON DELETE CASCADE,
    step_order      INT         NOT NULL,
    step_label      VARCHAR(200) NOT NULL,
    approver_role   VARCHAR(100) NOT NULL,      -- role that can approve this step
    timeout_hours   INT,                        -- step-level timeout (overrides def-level)
    escalate_to_role VARCHAR(100)               -- role to escalate to on timeout
);

-- ── Runtime: Instances ───────────────────────────────────────────────────────

CREATE TABLE workflow.record_workflow_state (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID        NOT NULL UNIQUE,   -- one active state per record
    org_id          UUID        NOT NULL,
    object_type     VARCHAR(100) NOT NULL,
    workflow_id     UUID        NOT NULL REFERENCES workflow.workflow_definition(id),
    current_state   VARCHAR(50)  NOT NULL,
    updated_by      UUID,
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE workflow.transition_history (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID        NOT NULL,
    org_id          UUID        NOT NULL,
    workflow_id     UUID        NOT NULL,
    from_state      VARCHAR(50),
    to_state        VARCHAR(50)  NOT NULL,
    trigger_name    VARCHAR(100),
    comment         TEXT,
    transitioned_by UUID,
    transitioned_at TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_wf_record    ON workflow.record_workflow_state (record_id);
CREATE INDEX idx_wf_org_type  ON workflow.record_workflow_state (org_id, object_type);
CREATE INDEX idx_history      ON workflow.transition_history (record_id);

-- ── Approval Instances ───────────────────────────────────────────────────────

CREATE TABLE workflow.approval_instance (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID        NOT NULL,
    org_id          UUID        NOT NULL,
    approval_def_id UUID        NOT NULL REFERENCES workflow.approval_definition(id),
    transition_id   UUID        NOT NULL REFERENCES workflow.workflow_transition(id),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',  -- PENDING, APPROVED, REJECTED
    current_step    INT          NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE TABLE workflow.approval_step_decision (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_instance_id UUID       NOT NULL REFERENCES workflow.approval_instance(id),
    step_order          INT         NOT NULL,
    approver_user_id    UUID        NOT NULL,
    decision            VARCHAR(20)  NOT NULL,   -- APPROVED, REJECTED
    comment             TEXT,
    decided_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);
```

---

## Workflow Execution Flow

```
User triggers transition:
POST /records/{id}/status  { "trigger": "SUBMIT", "comment": "Ready for review" }
    │
    ▼
record-service publishes RecordTransitionRequestedEvent to Kafka
    │
    ▼
workflow-service consumes event
    │
    ├── Load WorkflowDefinition for record's objectType
    ├── Load current state from record_workflow_state
    ├── Find transition where from_state = current AND trigger = requested
    ├── Verify requesting user has an allowed role
    ├── Check transition.requires_approval
    │       │
    │       ├── false → execute immediately
    │       │       │
    │       │       ├── Update record_workflow_state
    │       │       ├── Insert transition_history row
    │       │       └── Publish WorkflowTransitionedEvent
    │       │
    │       └── true  → start approval
    │               │
    │               ├── Create approval_instance (status=PENDING)
    │               ├── Create first approval step
    │               └── Publish ApprovalRequestedEvent (notifies approver)
    │
    ▼
record-service consumes WorkflowTransitionedEvent
    │
    └── Updates record.status to new state
```

---

## Approval Execution Flow

```
Approver calls:
POST /approvals/{approvalInstanceId}/decide
     { "decision": "APPROVED", "comment": "Looks good" }
    │
    ▼
workflow-service
    │
    ├── Record approval_step_decision
    ├── Check approval type:
    │       SEQUENTIAL → advance to next step OR complete
    │       PARALLEL   → wait for all parallel approvers
    │       ANY_ONE    → complete as soon as one approves
    │
    ├── If all steps approved → publish WorkflowTransitionedEvent
    │       → record status updates
    │
    └── If any step rejected → publish ApprovalRejectedEvent
            → workflow moves to rejection state
```

---

## REST API (workflow-service)

### Workflow Definitions

```
GET    /workflow-definitions                    List
POST   /workflow-definitions                    Create
GET    /workflow-definitions/{id}               Get with states/transitions
PUT    /workflow-definitions/{id}               Update
POST   /workflow-definitions/{id}/states        Add state
POST   /workflow-definitions/{id}/transitions   Add transition
```

### Runtime

```
GET    /records/{recordId}/workflow             Current state + allowed transitions
POST   /records/{recordId}/transitions          Execute a transition (triggers Kafka event)
GET    /records/{recordId}/transitions/history  Transition audit trail
```

### Approvals

```
GET    /approvals/pending                        My pending approvals (filtered by user role)
GET    /approvals/{id}                           Approval instance detail
POST   /approvals/{id}/decide                    Approve or Reject
GET    /approvals/{id}/history                   Step-by-step decision trail
```

---

## Kafka Events

### Consumed by workflow-service

Topic: `platform.record.events` — event type `STATUS_TRANSITION_REQUESTED`

```java
// Published by record-service when user calls POST /records/{id}/status
@Data @Builder
public class TransitionRequestedEvent {
    private UUID    recordId;
    private UUID    orgId;
    private String  objectType;
    private String  currentStatus;
    private String  triggerName;
    private String  comment;
    private UUID    requestedBy;
    private Instant occurredAt;
}
```

### Published by workflow-service

Topic: `platform.workflow.events`

```java
@Data @Builder
public class WorkflowEvent {
    private String  eventType;          // TRANSITIONED, APPROVAL_REQUESTED, APPROVED, REJECTED
    private UUID    recordId;
    private UUID    orgId;
    private String  objectType;
    private String  fromState;
    private String  toState;
    private UUID    approvalInstanceId; // only for approval events
    private UUID    actorUserId;
    private String  comment;
    private Instant occurredAt;
}
```

---

## Timeout / Escalation (Phase 3 optional, Phase 5 automation)

For Phase 3 ship the schema. Implement escalation in Phase 5 (automation-service).

The automation-service polls `approval_instance` where:
```sql
SELECT * FROM workflow.approval_instance
WHERE status = 'PENDING'
  AND created_at < now() - (timeout_hours || ' hours')::interval
```

Then fires escalation actions defined in the approval definition.

---

## Application Config (workflow-service)

```yaml
spring:
  application:
    name: workflow-service
  datasource:
    url: jdbc:postgresql://localhost:5435/platformdb
    hikari:
      schema: workflow
  jpa:
    properties:
      hibernate:
        default_schema: workflow
  flyway:
    schemas: workflow
  kafka:
    bootstrap-servers: localhost:9092
    consumer:
      group-id: workflow-service
      auto-offset-reset: earliest
      key-deserializer: org.apache.kafka.common.serialization.StringDeserializer
      value-deserializer: org.springframework.kafka.support.serializer.JsonDeserializer
      properties:
        spring.json.trusted.packages: "com.lagu.platform.events"
```

---

## Implementation Checklist

- [ ] Create `apps/workflow-service` module
- [ ] Implement all workflow schema tables + Flyway migrations
- [ ] Implement `WorkflowDefinitionService` (CRUD for definitions/states/transitions)
- [ ] Implement `StateMachineEngine` (load definition, validate, execute transition)
- [ ] Implement `ApprovalEngine` (create instance, record decisions, advance steps)
- [ ] Implement Kafka consumer for `TransitionRequestedEvent`
- [ ] Implement Kafka producer for `WorkflowEvent`
- [ ] Implement `WorkflowStateService` (runtime state per record)
- [ ] Add `/records/{id}/workflow` endpoint to workflow-service
- [ ] Update record-service to publish `TransitionRequestedEvent` instead of updating status directly
- [ ] Update record-service to consume `WorkflowEvent` TRANSITIONED → update `record.status`
- [ ] Seed platform-level workflow definitions for initial object types
- [ ] Write integration tests (state machine transitions)
- [ ] Write integration tests (approval flow)
