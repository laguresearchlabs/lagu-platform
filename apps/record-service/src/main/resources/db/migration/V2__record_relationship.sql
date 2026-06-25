CREATE TABLE record_relationship (
    id                  UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID        NOT NULL,
    relationship_name   VARCHAR(100) NOT NULL,
    source_record_id    UUID        NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    target_record_id    UUID        NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    created_by          UUID,
    created_at          TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_source_target_rel UNIQUE (relationship_name, source_record_id, target_record_id)
);

CREATE INDEX idx_rel_source ON record_relationship (org_id, source_record_id);
CREATE INDEX idx_rel_target ON record_relationship (org_id, target_record_id);
CREATE INDEX idx_rel_name   ON record_relationship (org_id, relationship_name);
