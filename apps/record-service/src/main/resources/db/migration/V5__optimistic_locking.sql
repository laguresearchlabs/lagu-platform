-- Optimistic locking (@Version): concurrent writers now conflict (HTTP 409) instead of
-- last-write-wins silently discarding an update.
ALTER TABLE record              ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE record_verification ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
