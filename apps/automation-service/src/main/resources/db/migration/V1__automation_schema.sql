CREATE SCHEMA IF NOT EXISTS automation;

CREATE TABLE automation.trigger_definition (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID,
    name            VARCHAR(100) NOT NULL,
    label           VARCHAR(200) NOT NULL,
    description     TEXT,
    event_type      VARCHAR(100) NOT NULL,
    object_type     VARCHAR(100),
    conditions      JSONB,
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_trigger_name_org UNIQUE NULLS NOT DISTINCT (name, org_id)
);

CREATE TABLE automation.action_definition (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_id          UUID         NOT NULL REFERENCES automation.trigger_definition(id) ON DELETE CASCADE,
    action_type         VARCHAR(50)  NOT NULL,
    execution_order     INT          NOT NULL DEFAULT 0,
    config              JSONB        NOT NULL DEFAULT '{}',
    continue_on_failure BOOLEAN      NOT NULL DEFAULT true,
    is_active           BOOLEAN      NOT NULL DEFAULT true
);

CREATE TABLE automation.automation_run (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    trigger_id      UUID         NOT NULL REFERENCES automation.trigger_definition(id),
    record_id       UUID,
    org_id          UUID,
    event_type      VARCHAR(100),
    status          VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message   TEXT,
    started_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    completed_at    TIMESTAMPTZ
);

CREATE TABLE automation.action_run (
    id                UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    automation_run_id UUID         NOT NULL REFERENCES automation.automation_run(id),
    action_id         UUID         NOT NULL REFERENCES automation.action_definition(id),
    action_type       VARCHAR(50),
    status            VARCHAR(20)  NOT NULL DEFAULT 'PENDING',
    error_message     TEXT,
    executed_at       TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_trigger_org_event  ON automation.trigger_definition (org_id, event_type);
CREATE INDEX idx_trigger_obj_type   ON automation.trigger_definition (object_type) WHERE object_type IS NOT NULL;
CREATE INDEX idx_run_trigger        ON automation.automation_run (trigger_id);
CREATE INDEX idx_run_record         ON automation.automation_run (record_id);
CREATE INDEX idx_run_status         ON automation.automation_run (status);

-- Seed platform-level triggers (org_id IS NULL = platform-level)
INSERT INTO automation.trigger_definition (name, label, event_type, object_type, conditions, is_active)
VALUES
    ('vendor_approved_notify', 'Notify vendor on approval',
     'RECORD_STATUS_CHANGED', null,
     '[{"field":"currentStatus","operator":"EQ","value":"APPROVED"}]', true),
    ('vendor_rejected_notify', 'Notify vendor on rejection',
     'RECORD_STATUS_CHANGED', null,
     '[{"field":"currentStatus","operator":"EQ","value":"REJECTED"}]', true),
    ('approval_requested_notify', 'Notify approver on approval request',
     'APPROVAL_REQUESTED', null, null, true),
    ('approval_timeout_escalate', 'Escalate timed-out approvals',
     'APPROVAL_TIMEOUT', null, null, true)
ON CONFLICT (name, org_id) DO NOTHING;
