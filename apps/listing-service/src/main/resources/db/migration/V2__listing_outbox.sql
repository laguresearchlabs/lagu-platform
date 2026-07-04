-- Transactional outbox for listing events (same pattern as record/workflow/schema services).
-- Snapshot (un)publication and its consumer-search-index event commit atomically; the relay
-- delivers to Kafka with retries.
CREATE TABLE listing_outbox (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(200) NOT NULL,
    payload_type VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_listing_outbox_unpublished ON listing_outbox (created_at) WHERE published_at IS NULL;
