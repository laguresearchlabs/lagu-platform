CREATE TABLE record (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL,
    object_type VARCHAR(100) NOT NULL,
    status      VARCHAR(50)  NOT NULL DEFAULT 'DRAFT',
    data        JSONB        NOT NULL DEFAULT '{}',
    created_by  UUID,
    updated_by  UUID,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_record_data_gin   ON record USING gin(data);
CREATE INDEX idx_record_org_type   ON record (org_id, object_type);
CREATE INDEX idx_record_org_status ON record (org_id, object_type, status);
CREATE INDEX idx_record_created_at ON record (org_id, object_type, created_at DESC);

CREATE TABLE record_audit (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id   UUID        NOT NULL REFERENCES record(id),
    action      VARCHAR(20)  NOT NULL,
    old_data    JSONB,
    new_data    JSONB,
    old_status  VARCHAR(50),
    new_status  VARCHAR(50),
    changed_by  UUID,
    changed_at  TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_audit_record ON record_audit (record_id);
