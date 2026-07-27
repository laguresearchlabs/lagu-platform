-- Transactional outbox for booking events (same pattern as record/workflow/schema/listing
-- services). Status mutations and the BookingEvent commit atomically; the relay delivers to
-- Kafka with retries.
CREATE TABLE booking_outbox (
    id           UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    topic        VARCHAR(100) NOT NULL,
    event_key    VARCHAR(200) NOT NULL,
    payload_type VARCHAR(200) NOT NULL,
    payload      TEXT         NOT NULL,
    attempts     INT          NOT NULL DEFAULT 0,
    created_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    published_at TIMESTAMPTZ
);

CREATE INDEX idx_booking_outbox_unpublished ON booking_outbox (created_at) WHERE published_at IS NULL;
