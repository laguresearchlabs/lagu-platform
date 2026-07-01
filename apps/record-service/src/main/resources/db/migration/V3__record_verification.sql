CREATE TABLE record_verification (
    id              UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID        NOT NULL REFERENCES record(id) ON DELETE CASCADE,
    org_id          UUID        NOT NULL,
    tier            VARCHAR(30) NOT NULL DEFAULT 'NONE',
    status          VARCHAR(20) NOT NULL DEFAULT 'UNVERIFIED',
    verified_by     UUID,
    verified_at     TIMESTAMPTZ,
    expires_at      TIMESTAMPTZ,
    notes           TEXT,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT uq_record_verification UNIQUE (record_id)
);

CREATE INDEX idx_verif_org   ON record_verification (org_id);
CREATE INDEX idx_verif_tier  ON record_verification (tier, status);
CREATE INDEX idx_verif_expiry ON record_verification (expires_at) WHERE status = 'VERIFIED';
