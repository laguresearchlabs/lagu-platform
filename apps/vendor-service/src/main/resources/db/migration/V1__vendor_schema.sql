-- Vendor profile table: one row per vendor org.
-- The orgId IS the vendorId — each vendor is its own org.
CREATE TABLE IF NOT EXISTS vendor_profile (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID        NOT NULL UNIQUE,    -- platform orgId (= vendorId)
    record_id       UUID        NOT NULL UNIQUE,    -- corresponding VENDOR record in record-service
    owner_user_id   UUID        NOT NULL,           -- IAM userId of the registering owner
    business_name   VARCHAR(255) NOT NULL,
    status          VARCHAR(30) NOT NULL DEFAULT 'DRAFT',
                                                    -- DRAFT | SUBMITTED | UNDER_REVIEW | ACTIVE | SUSPENDED | REJECTED
    country         VARCHAR(10) NOT NULL DEFAULT 'IN',
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_vendor_profile_status ON vendor_profile (status);

-- Vendor team members: additional users linked to the vendor org
CREATE TABLE IF NOT EXISTS vendor_member (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL,
    user_id     UUID        NOT NULL,
    role        VARCHAR(30) NOT NULL DEFAULT 'MEMBER',  -- OWNER | ADMIN | MEMBER
    invited_by  UUID,
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_vendor_member_org ON vendor_member (org_id);
CREATE INDEX IF NOT EXISTS idx_vendor_member_user ON vendor_member (user_id);

-- KYC readiness checklist snapshot (recomputed on demand, cached here for speed)
CREATE TABLE IF NOT EXISTS vendor_kyc_checklist (
    org_id                  UUID PRIMARY KEY REFERENCES vendor_profile(org_id),
    has_gst_doc             BOOLEAN NOT NULL DEFAULT FALSE,
    has_pan_doc             BOOLEAN NOT NULL DEFAULT FALSE,
    has_bank_doc            BOOLEAN NOT NULL DEFAULT FALSE,
    has_identity_doc        BOOLEAN NOT NULL DEFAULT FALSE,
    business_name_filled    BOOLEAN NOT NULL DEFAULT FALSE,
    address_filled          BOOLEAN NOT NULL DEFAULT FALSE,
    phone_filled            BOOLEAN NOT NULL DEFAULT FALSE,
    kyc_ready               BOOLEAN NOT NULL DEFAULT FALSE,
    last_computed_at        TIMESTAMPTZ NOT NULL DEFAULT now()
);
