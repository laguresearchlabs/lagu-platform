-- event_member: soft-delete (REMOVED joins INVITED|ACCEPTED|DECLINED) + role-change audit.
-- No new status column needed (already has one) — REMOVED is just a new valid value.
ALTER TABLE event_member ADD COLUMN removed_by  UUID;
ALTER TABLE event_member ADD COLUMN removed_at  TIMESTAMPTZ;
ALTER TABLE event_member ADD COLUMN updated_by  UUID;
ALTER TABLE event_member ADD COLUMN updated_at  TIMESTAMPTZ;

ALTER TABLE event_member DROP CONSTRAINT event_member_org_id_user_id_key;
CREATE UNIQUE INDEX idx_event_member_org_user_active
    ON event_member (org_id, user_id) WHERE status <> 'REMOVED';
