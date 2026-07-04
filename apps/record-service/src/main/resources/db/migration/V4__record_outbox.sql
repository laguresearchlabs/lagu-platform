-- Transactional outbox: events are written here in the same transaction as the record
-- change that caused them, then relayed to Kafka by OutboxRelay. This removes the
-- dual-write race where a DB commit succeeded but the Kafka publish was lost (stale
-- search index / snapshots) or a publish escaped a rolled-back transaction.
CREATE TABLE record_outbox (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(200) NOT NULL,
    payload_type VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_outbox_unpublished ON record_outbox (created_at) WHERE published_at IS NULL;
