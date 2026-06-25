-- ── Workflow Definitions ──────────────────────────────────────────────────────

CREATE TABLE workflow_definition (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID,
    name           VARCHAR(100) NOT NULL,
    label          VARCHAR(200) NOT NULL,
    object_type    VARCHAR(100) NOT NULL,
    initial_status VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    is_active      BOOLEAN      NOT NULL DEFAULT true,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- platform-level workflows: two entries with same object_type and NULL org_id must conflict
    CONSTRAINT uq_workflow_object_org UNIQUE NULLS NOT DISTINCT (object_type, org_id)
);

CREATE TABLE workflow_state (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id   UUID         NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,
    name          VARCHAR(50)  NOT NULL,
    label         VARCHAR(100) NOT NULL,
    description   TEXT,
    is_terminal   BOOLEAN      NOT NULL DEFAULT false,
    display_order INT          NOT NULL DEFAULT 0,
    color         VARCHAR(20),
    CONSTRAINT uq_state_name_workflow UNIQUE (workflow_id, name)
);

-- ── Approval Definitions (must precede workflow_transition) ───────────────────

CREATE TABLE approval_definition (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    name          VARCHAR(100) NOT NULL,
    label         VARCHAR(200) NOT NULL,
    approval_type VARCHAR(20)  NOT NULL DEFAULT 'SEQUENTIAL',
    timeout_hours INT,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE approval_step (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_def_id  UUID         NOT NULL REFERENCES approval_definition(id) ON DELETE CASCADE,
    step_order       INT          NOT NULL,
    step_label       VARCHAR(200) NOT NULL,
    approver_role    VARCHAR(100) NOT NULL,
    timeout_hours    INT,
    escalate_to_role VARCHAR(100)
);

-- ── Workflow Transitions ──────────────────────────────────────────────────────

CREATE TABLE workflow_transition (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    workflow_id       UUID         NOT NULL REFERENCES workflow_definition(id) ON DELETE CASCADE,
    from_state        VARCHAR(50)  NOT NULL,
    to_state          VARCHAR(50)  NOT NULL,
    trigger_name      VARCHAR(100) NOT NULL,
    trigger_label     VARCHAR(200),
    allowed_roles     JSONB,
    requires_approval BOOLEAN      NOT NULL DEFAULT false,
    approval_def_id   UUID         REFERENCES approval_definition(id),
    conditions        JSONB,
    CONSTRAINT uq_transition UNIQUE (workflow_id, from_state, trigger_name)
);

-- ── Runtime: State per Record ────────────────────────────────────────────────

CREATE TABLE record_workflow_state (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id     UUID         NOT NULL UNIQUE,
    org_id        UUID         NOT NULL,
    object_type   VARCHAR(100) NOT NULL,
    workflow_id   UUID         NOT NULL REFERENCES workflow_definition(id),
    current_state VARCHAR(50)  NOT NULL,
    updated_by    UUID,
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE TABLE transition_history (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id        UUID         NOT NULL,
    org_id           UUID         NOT NULL,
    workflow_id      UUID         NOT NULL,
    from_state       VARCHAR(50),
    to_state         VARCHAR(50)  NOT NULL,
    trigger_name     VARCHAR(100),
    comment          TEXT,
    transitioned_by  UUID,
    transitioned_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── Approval Runtime ─────────────────────────────────────────────────────────

CREATE TABLE approval_instance (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID        NOT NULL,
    org_id          UUID        NOT NULL,
    approval_def_id UUID        NOT NULL REFERENCES approval_definition(id),
    transition_id   UUID        NOT NULL REFERENCES workflow_transition(id),
    status          VARCHAR(20) NOT NULL DEFAULT 'PENDING',
    current_step    INT         NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE TABLE approval_step_decision (
    id                   UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    approval_instance_id UUID        NOT NULL REFERENCES approval_instance(id),
    step_order           INT         NOT NULL,
    approver_user_id     UUID        NOT NULL,
    decision             VARCHAR(20) NOT NULL,
    comment              TEXT,
    decided_at           TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Indexes ───────────────────────────────────────────────────────────────────

CREATE INDEX idx_wf_def_org_type  ON workflow_definition (org_id, object_type);
CREATE INDEX idx_wf_state         ON workflow_state (workflow_id, display_order);
CREATE INDEX idx_wf_transition     ON workflow_transition (workflow_id, from_state);
CREATE INDEX idx_rws_record       ON record_workflow_state (record_id);
CREATE INDEX idx_rws_org_type     ON record_workflow_state (org_id, object_type);
CREATE INDEX idx_history_record   ON transition_history (record_id);
CREATE INDEX idx_approval_inst    ON approval_instance (record_id, status);
CREATE INDEX idx_approval_decision ON approval_step_decision (approval_instance_id);
