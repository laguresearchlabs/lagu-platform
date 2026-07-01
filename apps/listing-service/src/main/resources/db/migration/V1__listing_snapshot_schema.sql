-- Approved consumer-facing frozen copies of listings.
-- One row per listing; overwritten on each approval.
CREATE TABLE IF NOT EXISTS listing_snapshot (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID        NOT NULL UNIQUE,    -- matches record-service record id
    org_id          UUID        NOT NULL,
    object_type     VARCHAR(100) NOT NULL,           -- VENUE | PHOTOGRAPHER | CATERER | ...
    data            JSONB        NOT NULL DEFAULT '{}',
    status          VARCHAR(30)  NOT NULL DEFAULT 'PUBLISHED',
    verification_tier VARCHAR(20) NOT NULL DEFAULT 'NONE',
    search_boost    NUMERIC(5,2) NOT NULL DEFAULT 1.0,
    published_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    version         BIGINT       NOT NULL DEFAULT 1,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_snapshot_org_type ON listing_snapshot (org_id, object_type);
CREATE INDEX IF NOT EXISTS idx_snapshot_type_boost ON listing_snapshot (object_type, search_boost DESC);

-- Listing availability slots
CREATE TABLE IF NOT EXISTS listing_availability (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    record_id       UUID         NOT NULL,
    org_id          UUID         NOT NULL,
    slot_date       DATE         NOT NULL,
    slot_type       VARCHAR(20)  NOT NULL DEFAULT 'AVAILABLE',  -- AVAILABLE | BLOCKED | BOOKED
    booking_ref     UUID,
    note            TEXT,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    UNIQUE (record_id, slot_date)
);

CREATE INDEX IF NOT EXISTS idx_availability_record_date ON listing_availability (record_id, slot_date);
CREATE INDEX IF NOT EXISTS idx_availability_date_type ON listing_availability (slot_date, slot_type);
