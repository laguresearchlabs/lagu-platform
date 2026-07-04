-- Optimistic locking (@Version): vendor profile edits and admin status changes now conflict
-- (HTTP 409) instead of silently overwriting each other.
ALTER TABLE vendor_profile ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
