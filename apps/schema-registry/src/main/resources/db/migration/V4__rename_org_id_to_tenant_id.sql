-- org_id never referred to a real Organization entity (NULL = platform-level, otherwise the
-- owning vendor org's id) — renaming to tenant_id platform-wide to stop implying a lookup
-- that doesn't exist.
ALTER TABLE field_definition RENAME COLUMN org_id TO tenant_id;
ALTER TABLE field_definition RENAME CONSTRAINT uq_field_name_org TO uq_field_name_tenant;
ALTER INDEX idx_field_org RENAME TO idx_field_tenant;

ALTER TABLE field_group RENAME COLUMN org_id TO tenant_id;
ALTER TABLE field_group RENAME CONSTRAINT uq_field_group_name_org TO uq_field_group_name_tenant;
ALTER INDEX idx_field_group_org RENAME TO idx_field_group_tenant;

ALTER TABLE listing_type_definition RENAME COLUMN org_id TO tenant_id;
ALTER TABLE listing_type_definition RENAME CONSTRAINT uq_listing_type_name_org TO uq_listing_type_name_tenant;
ALTER INDEX idx_listing_type_org RENAME TO idx_listing_type_tenant;

ALTER TABLE category_definition RENAME COLUMN org_id TO tenant_id;
ALTER TABLE category_definition RENAME CONSTRAINT uq_category_slug_org TO uq_category_slug_tenant;
ALTER INDEX idx_category_org RENAME TO idx_category_tenant;

ALTER TABLE document_requirement RENAME COLUMN org_id TO tenant_id;
ALTER TABLE document_requirement RENAME CONSTRAINT uq_doc_req_code_org TO uq_doc_req_code_tenant;
ALTER INDEX idx_doc_req_org RENAME TO idx_doc_req_tenant;

ALTER TABLE relationship_definition RENAME COLUMN org_id TO tenant_id;
ALTER TABLE relationship_definition RENAME CONSTRAINT uq_rel_def_name_org TO uq_rel_def_name_tenant;
ALTER INDEX idx_rel_def_org RENAME TO idx_rel_def_tenant;
