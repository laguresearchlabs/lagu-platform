-- Transactional outbox for schema events (same pattern as record-service/workflow-service).
-- A published schema version and its SCHEMA_PUBLISHED event now commit atomically; the
-- relay delivers to Kafka with retries instead of a fire-and-forget send that could lose
-- the event (leaving record-service/search-service caches unaware of the new version).
CREATE TABLE schema_outbox (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(200) NOT NULL,
    payload_type VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_schema_outbox_unpublished ON schema_outbox (created_at) WHERE published_at IS NULL;
