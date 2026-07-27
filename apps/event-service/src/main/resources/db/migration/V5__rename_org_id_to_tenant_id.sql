-- org_id never referred to a real Organization entity — it's always just whichever business
-- entity owns the data (here, Event.id itself, see Event.getTenantId()).
-- Renaming to tenant_id platform-wide to stop implying a lookup that doesn't exist.
ALTER TABLE event_member RENAME COLUMN org_id TO tenant_id;
ALTER INDEX idx_event_member_org RENAME TO idx_event_member_tenant;
ALTER INDEX idx_event_member_org_user_active RENAME TO idx_event_member_tenant_user_active;

ALTER TABLE event_join_request RENAME COLUMN org_id TO tenant_id;
ALTER TABLE event_join_request RENAME CONSTRAINT event_join_request_org_id_user_id_key TO event_join_request_tenant_id_user_id_key;
ALTER INDEX idx_event_join_request_org RENAME TO idx_event_join_request_tenant;
