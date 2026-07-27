-- A booking links a consumer user to a vendor org's listing for one date. No record-service
-- Record and no workflow-service state machine here (see booking-service README) — the status
-- lifecycle is small, fixed, and asymmetric (only the vendor side may quote, only the consumer
-- side may confirm), and Confirm must claim listing-service's availability slot in the same
-- logical operation, which an async workflow engine round-trip is a poor fit for.
CREATE TABLE IF NOT EXISTS booking (
    id                    UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    consumer_user_id      UUID NOT NULL,
    vendor_id             UUID NOT NULL,      -- VendorProfile.orgId (already the public vendor id)
    listing_record_id     UUID NOT NULL,
    event_id              UUID,               -- nullable; event-service Event.id, optional origin
    event_date            DATE NOT NULL,
    status                VARCHAR(30) NOT NULL DEFAULT 'INQUIRY',
                                              -- INQUIRY | QUOTED | CONFIRMED | COMPLETED | CANCELLED
    inquiry_message       TEXT,
    quoted_price          NUMERIC(12,2),
    currency              VARCHAR(3) NOT NULL DEFAULT 'INR',
    commission_rate       NUMERIC(5,2),       -- frozen from schema-registry TierConfiguration at Quote
                                              -- time; a PERCENTAGE (20.00 = 20%), matching TierConfiguration.commissionRate
    commission_amount     NUMERIC(12,2),      -- frozen: quoted_price * commission_rate / 100
    quote_note            TEXT,
    cancelled_by_user_id  UUID,
    cancel_reason         TEXT,
    availability_claimed  BOOLEAN NOT NULL DEFAULT false,
    version               BIGINT NOT NULL DEFAULT 0,
    created_at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_booking_consumer ON booking (consumer_user_id);
CREATE INDEX IF NOT EXISTS idx_booking_vendor ON booking (vendor_id);
CREATE INDEX IF NOT EXISTS idx_booking_listing ON booking (listing_record_id, event_date);
CREATE INDEX IF NOT EXISTS idx_booking_event ON booking (event_id) WHERE event_id IS NOT NULL;
