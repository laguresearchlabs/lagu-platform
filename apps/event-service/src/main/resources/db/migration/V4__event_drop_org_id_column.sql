-- Event.id doubles as the org-partition key now (see Event.java) — org_id was always unique
-- per event and never diverged from id, so the separate column was pure redundancy.
ALTER TABLE event DROP COLUMN org_id;
