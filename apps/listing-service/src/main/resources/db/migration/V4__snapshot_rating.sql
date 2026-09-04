-- What consumers have said about this listing, as two numbers on the snapshot.
--
-- The reviews themselves live in booking-service, where the fact that makes them worth trusting —
-- that the reviewer actually bought something — is local knowledge. What lives here is only the
-- aggregate, and it lives here for one reason: the snapshot is what becomes a `ListingEvent` and
-- therefore what search-service indexes. A rating that is not on the snapshot cannot appear on a
-- marketplace card without the grid fetching it per tile, which is twenty round trips for one
-- page of results.
--
-- Nullable average, and deliberately: "nobody has reviewed this" and "everybody scored it badly"
-- are different claims that have to render differently, and a zero would collapse them. A genuine
-- average of zero cannot occur — booking-service's rating floor is 1.
ALTER TABLE listing_snapshot
    ADD COLUMN rating_average NUMERIC(2,1),
    ADD COLUMN review_count   INTEGER NOT NULL DEFAULT 0;

-- Held to what a star rating can actually mean, so a bad write is refused here rather than
-- surfacing as 4.7999999999999998 on a card.
ALTER TABLE listing_snapshot
    ADD CONSTRAINT ck_snapshot_rating_range
    CHECK (rating_average IS NULL OR rating_average BETWEEN 1.0 AND 5.0);

-- The two must agree: an average with nothing behind it, or a count with no average, is a bug in
-- whatever wrote them rather than a state the marketplace should try to render.
ALTER TABLE listing_snapshot
    ADD CONSTRAINT ck_snapshot_rating_coherent
    CHECK ((rating_average IS NULL AND review_count = 0)
        OR (rating_average IS NOT NULL AND review_count > 0));
