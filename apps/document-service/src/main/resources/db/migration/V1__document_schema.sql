CREATE SCHEMA IF NOT EXISTS documents;

CREATE TABLE documents.document (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID         NOT NULL,
    user_id             UUID         NOT NULL,

    -- Document classification
    document_type       VARCHAR(50)  NOT NULL,   -- RESUME | IDENTITY_PROOF | PHOTOGRAPH | ACADEMIC_CERTIFICATE | ADDRESS_PROOF | OTHER
    identity_sub_type   VARCHAR(50),             -- AADHAAR | PASSPORT | DRIVING_LICENSE | VOTER_ID | PAN_CARD (IDENTITY_PROOF only)

    -- File metadata
    file_name           VARCHAR(500) NOT NULL,
    file_url            TEXT         NOT NULL,   -- URL from image-service
    mime_type           VARCHAR(100),
    file_size_bytes     BIGINT,

    -- Review lifecycle
    status              VARCHAR(30)  NOT NULL DEFAULT 'UPLOADED',  -- UPLOADED | UNDER_REVIEW | VERIFIED | REJECTED | EXPIRED
    rejection_reason    TEXT,
    reviewed_by         UUID,                    -- userId of HR reviewer
    reviewed_at         TIMESTAMPTZ,

    -- Optional document metadata
    expiry_date         DATE,                    -- for identity documents
    metadata            JSONB,                   -- any additional key-value pairs

    uploaded_at         TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_doc_user_org
    ON documents.document (user_id, org_id, document_type, status);

CREATE INDEX idx_doc_org_status
    ON documents.document (org_id, status, uploaded_at DESC);

CREATE INDEX idx_doc_type_user
    ON documents.document (org_id, user_id, document_type);
