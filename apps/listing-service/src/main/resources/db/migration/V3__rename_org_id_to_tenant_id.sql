-- org_id never referred to a real Organization entity — it's the owning vendor's id. Renaming
-- to tenant_id platform-wide to stop implying a lookup that doesn't exist.
ALTER TABLE listing_snapshot RENAME COLUMN org_id TO tenant_id;
ALTER INDEX idx_snapshot_org_type RENAME TO idx_snapshot_tenant_type;

ALTER TABLE listing_availability RENAME COLUMN org_id TO tenant_id;
