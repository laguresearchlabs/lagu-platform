-- Transactional outbox for workflow events (same pattern as record-service's record_outbox).
-- Replaces the AFTER_COMMIT Kafka listener: that design never sent events for rolled-back
-- transactions (good) but lost events forever when the post-commit send failed or the process
-- crashed between commit and send (bad). Rows written here commit atomically with the state
-- change; OutboxRelay delivers them to Kafka with retries.
CREATE TABLE workflow_outbox (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(200) NOT NULL,
    payload_type VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_workflow_outbox_unpublished ON workflow_outbox (created_at) WHERE published_at IS NULL;
