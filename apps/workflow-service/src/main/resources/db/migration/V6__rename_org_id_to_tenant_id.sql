-- org_id never referred to a real Organization entity — renaming to tenant_id platform-wide.
ALTER TABLE workflow_definition RENAME COLUMN org_id TO tenant_id;
ALTER TABLE workflow_definition RENAME CONSTRAINT uq_workflow_object_org TO uq_workflow_object_tenant;
ALTER INDEX idx_wf_def_org_type RENAME TO idx_wf_def_tenant_type;

ALTER TABLE record_workflow_state RENAME COLUMN org_id TO tenant_id;
ALTER INDEX idx_rws_org_type RENAME TO idx_rws_tenant_type;

ALTER TABLE transition_history RENAME COLUMN org_id TO tenant_id;

ALTER TABLE approval_instance RENAME COLUMN org_id TO tenant_id;

ALTER TABLE change_set RENAME COLUMN org_id TO tenant_id;
ALTER INDEX idx_change_set_org_status RENAME TO idx_change_set_tenant_status;
