-- VendorProfile.id doubles as the org-partition key now (see VendorProfile.java) — org_id was
-- always unique per vendor and never diverged from id, so the separate column was pure redundancy.
ALTER TABLE vendor_kyc_checklist DROP CONSTRAINT vendor_kyc_checklist_org_id_fkey;
ALTER TABLE vendor_kyc_checklist ADD CONSTRAINT vendor_kyc_checklist_org_id_fkey
    FOREIGN KEY (org_id) REFERENCES vendor_profile(id);

ALTER TABLE vendor_profile DROP COLUMN org_id;
ALTER TABLE vendor_profile ALTER COLUMN id DROP DEFAULT;
