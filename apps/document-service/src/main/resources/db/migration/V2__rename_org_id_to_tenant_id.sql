-- org_id never referred to a real Organization entity — renaming to tenant_id platform-wide.
ALTER TABLE documents.document RENAME COLUMN org_id TO tenant_id;
ALTER INDEX documents.idx_doc_user_org RENAME TO idx_doc_user_tenant;
ALTER INDEX documents.idx_doc_org_status RENAME TO idx_doc_tenant_status;
