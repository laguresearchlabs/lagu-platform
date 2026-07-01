-- Task 6: requiresChangeApproval on workflow_state
ALTER TABLE workflow_state
    ADD COLUMN IF NOT EXISTS requires_change_approval BOOLEAN NOT NULL DEFAULT FALSE;

-- Task 7: ChangeSet domain — staging field-level edits for admin review
CREATE TABLE IF NOT EXISTS change_set (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID        NOT NULL,
    org_id          UUID        NOT NULL,
    object_type     VARCHAR(100),
    workflow_id     UUID        REFERENCES workflow_definition(id),
    status          VARCHAR(30) NOT NULL DEFAULT 'PENDING',   -- PENDING | APPROVED | REJECTED | WITHDRAWN
    proposed_data   JSONB       NOT NULL DEFAULT '{}',        -- full proposed field map
    original_data   JSONB       NOT NULL DEFAULT '{}',        -- snapshot of fields before change
    corrected_data  JSONB,                                    -- admin corrections (optional)
    submitted_by    UUID        NOT NULL,
    submitted_at    TIMESTAMPTZ NOT NULL DEFAULT now(),
    reviewed_by     UUID,
    reviewed_at     TIMESTAMPTZ,
    admin_comment   TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_change_set_record_id ON change_set (record_id);
CREATE INDEX IF NOT EXISTS idx_change_set_org_status ON change_set (org_id, status);

-- Task 8: index to support TransitionGuard condition evaluation by workflow
CREATE INDEX IF NOT EXISTS idx_workflow_transition_workflow_id ON workflow_transition (workflow_id);
