-- Transactional outbox for event-service, matching booking/record/workflow/schema/listing.
--
-- Added for invitation emails. An invitation to somebody with no account is worth nothing until
-- they are told about it, and telling them means an AutomationEvent for notification-service —
-- which must commit with the invitation row or not at all. Sending to Kafka directly would give
-- the two failure modes this table exists to remove: an email about an invitation that rolled
-- back, and an invitation nobody was ever told about.
CREATE TABLE event_outbox (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(200) NOT NULL,
    payload_type VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_event_outbox_unpublished ON event_outbox (created_at) WHERE published_at IS NULL;
