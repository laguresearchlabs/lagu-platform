-- org_id never referred to a real Organization entity — it's the id of whichever domain entity
-- (Vendor, Event, ...) owns the record (see docs on RecordService.findForContext). Renaming to
-- tenant_id platform-wide to stop implying a lookup that doesn't exist.
ALTER TABLE record RENAME COLUMN org_id TO tenant_id;
ALTER INDEX idx_record_org_type RENAME TO idx_record_tenant_type;
ALTER INDEX idx_record_org_status RENAME TO idx_record_tenant_status;

ALTER TABLE record_relationship RENAME COLUMN org_id TO tenant_id;

ALTER TABLE record_verification RENAME COLUMN org_id TO tenant_id;
ALTER INDEX idx_verif_org RENAME TO idx_verif_tenant;
