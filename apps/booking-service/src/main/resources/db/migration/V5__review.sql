-- Consumer reviews of a vendor's listing.
--
-- Here rather than in record-service, where EVENT_POST and EVENT_COMMENT live, because the thing
-- that makes a marketplace rating worth trusting is that the reviewer actually bought something —
-- and whether they did is booking-service's own knowledge. A review as a record would have to ask
-- across a service boundary for the one fact that matters about it.
--
-- `booking_id` is nullable and that is the whole shape of the design. A review WITH one is
-- verified: the booking completed, and it belongs to the author. A review without one is somebody
-- who never booked through the platform, which is allowed and is marked as such — only verified
-- reviews count toward the average a shopper sees, so an unverified opinion is visible without
-- being able to move the number.
CREATE TABLE IF NOT EXISTS review (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_record_id  UUID NOT NULL,
    vendor_id          UUID NOT NULL,      -- denormalised: the vendor-side read filters on it
    booking_id         UUID,               -- the COMPLETED booking, when there is one
    author_user_id     UUID NOT NULL,
    rating             SMALLINT NOT NULL,
    body               TEXT,
    -- Frozen at write time, never recomputed. A booking cancelled or refunded afterwards does not
    -- retroactively un-verify a review that was true when it was written.
    verified           BOOLEAN NOT NULL DEFAULT false,
    -- The vendor's right of reply, shown beneath the review. One per review: a thread would put
    -- the platform in the middle of an argument, which is the thing a reply exists to avoid.
    vendor_reply       TEXT,
    vendor_replied_at  TIMESTAMPTZ,
    vendor_replied_by  UUID,
    created_at         TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ NOT NULL DEFAULT now(),

    CONSTRAINT ck_review_rating_range CHECK (rating BETWEEN 1 AND 5),
    -- A reply cannot exist without words in it, and its timestamp cannot exist without the reply.
    CONSTRAINT ck_review_reply_coherent CHECK (
        (vendor_reply IS NULL AND vendor_replied_at IS NULL)
        OR (vendor_reply IS NOT NULL AND vendor_replied_at IS NOT NULL)
    )
);

-- One review per person per listing. Without this a single account can push a listing's average
-- wherever it likes by writing the same opinion twenty times — the cheapest possible attack on the
-- one number a shopper trusts.
CREATE UNIQUE INDEX IF NOT EXISTS uq_review_author_listing
    ON review (listing_record_id, author_user_id);

-- One review per booking, and only where there is one: the partial predicate is what lets the many
-- unverified rows coexist instead of all colliding on NULL.
CREATE UNIQUE INDEX IF NOT EXISTS uq_review_booking
    ON review (booking_id) WHERE booking_id IS NOT NULL;

-- The two reads this table exists for: a listing's reviews, newest first, and the aggregate behind
-- a card's "4.8 (62)" — which counts verified rows only, hence the column in the index.
CREATE INDEX IF NOT EXISTS idx_review_listing
    ON review (listing_record_id, verified, created_at DESC);

-- The vendor's own queue: everything said about them, so a reply is one screen rather than a hunt
-- through their listings.
CREATE INDEX IF NOT EXISTS idx_review_vendor
    ON review (vendor_id, created_at DESC);
