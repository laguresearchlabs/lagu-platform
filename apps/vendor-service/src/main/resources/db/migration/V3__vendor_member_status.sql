-- Adds soft-delete + role-change audit to vendor_member. Previously remove() hard-deleted
-- (memberRepo.delete), and UNIQUE(org_id, user_id) meant a removed user could never be
-- re-invited without a constraint violation. Status now distinguishes ACTIVE from REMOVED,
-- and the unique constraint is relaxed to a partial index that ignores REMOVED rows.
ALTER TABLE vendor_member ADD COLUMN status VARCHAR(30) NOT NULL DEFAULT 'ACTIVE'; -- ACTIVE | REMOVED
ALTER TABLE vendor_member ADD COLUMN removed_by  UUID;
ALTER TABLE vendor_member ADD COLUMN removed_at  TIMESTAMPTZ;
ALTER TABLE vendor_member ADD COLUMN updated_by  UUID;
ALTER TABLE vendor_member ADD COLUMN updated_at  TIMESTAMPTZ;

ALTER TABLE vendor_member DROP CONSTRAINT vendor_member_org_id_user_id_key;
CREATE UNIQUE INDEX idx_vendor_member_org_user_active
    ON vendor_member (org_id, user_id) WHERE status <> 'REMOVED';
