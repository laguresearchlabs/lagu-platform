-- Optimistic locking (@Version): two concurrent transitions/approval decisions now conflict
-- instead of both applying against the same stale state.
ALTER TABLE record_workflow_state ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
ALTER TABLE approval_instance     ADD COLUMN version BIGINT NOT NULL DEFAULT 0;
