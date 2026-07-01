-- ── Document Type Definitions ─────────────────────────────────────────────────

CREATE TABLE document_type_definition (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID,
    object_type         VARCHAR(100),
    code                VARCHAR(100) NOT NULL,
    label               VARCHAR(200) NOT NULL,
    description         TEXT,
    is_required         BOOLEAN      NOT NULL DEFAULT false,
    expiry_tracked      BOOLEAN      NOT NULL DEFAULT false,
    allowed_mime_types  JSONB,
    max_size_mb         INT          NOT NULL DEFAULT 5,
    is_active           BOOLEAN      NOT NULL DEFAULT true,
    display_order       INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_doc_type_code_org UNIQUE (code, org_id)
);

CREATE INDEX idx_doc_type_org     ON document_type_definition (org_id);
CREATE INDEX idx_doc_type_objtype ON document_type_definition (object_type);

-- ── Tier Configurations ───────────────────────────────────────────────────────

CREATE TABLE tier_configuration (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tier_name               VARCHAR(30)  NOT NULL,
    object_type             VARCHAR(100),
    commission_rate         DECIMAL(5,2) NOT NULL DEFAULT 20.00,
    max_active_bookings     INT,
    search_boost_factor     DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    response_sla_hours      INT          NOT NULL DEFAULT 48,
    features                JSONB,
    is_active               BOOLEAN      NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_tier_name_objtype UNIQUE (tier_name, object_type)
);

CREATE INDEX idx_tier_name ON tier_configuration (tier_name);

-- ── Country Validation Configs ────────────────────────────────────────────────

CREATE TABLE country_validation_config (
    id          UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    country     VARCHAR(5)  NOT NULL UNIQUE,
    rules       JSONB       NOT NULL,
    currency    VARCHAR(5)  NOT NULL DEFAULT 'INR',
    tax_label   VARCHAR(20) NOT NULL DEFAULT 'GST',
    dial_code   VARCHAR(10),
    is_active   BOOLEAN     NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now()
);

-- ── Category Definitions ──────────────────────────────────────────────────────

CREATE TABLE category_definition (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID,
    parent_id     UUID         REFERENCES category_definition(id) ON DELETE SET NULL,
    object_type   VARCHAR(100),
    slug          VARCHAR(100) NOT NULL,
    label         VARCHAR(200) NOT NULL,
    description   TEXT,
    icon_url      VARCHAR(500),
    display_order INT          NOT NULL DEFAULT 0,
    is_active     BOOLEAN      NOT NULL DEFAULT true,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_category_slug_org UNIQUE (slug, org_id)
);

CREATE INDEX idx_category_parent   ON category_definition (parent_id);
CREATE INDEX idx_category_objtype  ON category_definition (object_type);
CREATE INDEX idx_category_org      ON category_definition (org_id);
