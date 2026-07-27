-- org_id never referred to a real Organization entity — it's always just whichever business
-- entity owns the data (here, VendorProfile.id itself, see VendorProfile.getTenantId()).
-- Renaming to tenant_id platform-wide to stop implying a lookup that doesn't exist.
ALTER TABLE vendor_member RENAME COLUMN org_id TO tenant_id;
ALTER INDEX idx_vendor_member_org RENAME TO idx_vendor_member_tenant;
ALTER INDEX idx_vendor_member_org_user_active RENAME TO idx_vendor_member_tenant_user_active;

ALTER TABLE vendor_kyc_checklist RENAME COLUMN org_id TO tenant_id;
ALTER TABLE vendor_kyc_checklist RENAME CONSTRAINT vendor_kyc_checklist_org_id_fkey TO vendor_kyc_checklist_tenant_id_fkey;
