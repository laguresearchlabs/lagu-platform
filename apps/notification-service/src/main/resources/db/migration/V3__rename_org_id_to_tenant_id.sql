-- org_id never referred to a real Organization entity — renaming to tenant_id platform-wide.
ALTER TABLE notification.notification RENAME COLUMN org_id TO tenant_id;
ALTER INDEX notification.idx_notification_org RENAME TO idx_notification_tenant;
