-- org_id never referred to a real Organization entity — renaming to tenant_id platform-wide.
ALTER TABLE automation.trigger_definition RENAME COLUMN org_id TO tenant_id;
ALTER TABLE automation.trigger_definition RENAME CONSTRAINT uq_trigger_name_org TO uq_trigger_name_tenant;
ALTER INDEX automation.idx_trigger_org_event RENAME TO idx_trigger_tenant_event;

ALTER TABLE automation.automation_run RENAME COLUMN org_id TO tenant_id;
