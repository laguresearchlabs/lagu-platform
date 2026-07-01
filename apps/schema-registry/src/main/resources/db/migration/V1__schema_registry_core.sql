-- =============================================================================
-- Schema Registry — V1 Core Schema
-- Absorbs and enhances metadata-service tables.
-- All tables live in the `schema_registry` PostgreSQL schema.
-- =============================================================================

-- ── Field Definitions (was: attribute_definition) ────────────────────────────
-- An individual field that can be placed in any FieldGroup.

CREATE TABLE field_definition (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID,                               -- NULL = platform-level

    -- Identity
    name                VARCHAR(100) NOT NULL,
    label               VARCHAR(200) NOT NULL,
    description         TEXT,

    -- Type
    field_type          VARCHAR(50)  NOT NULL,              -- see FieldType enum
    -- For ENUM / MULTI_SELECT: allowed values
    enum_values         JSONB,
    -- For ARRAY_OF_OBJECTS: inline schema of each item as JSONB array of FieldDefinition-like objects
    item_schema         JSONB,
    -- For ENTITY_REFERENCE: the target object type name
    reference_type      VARCHAR(100),

    -- Validation
    is_required         BOOLEAN      NOT NULL DEFAULT false,
    is_unique           BOOLEAN      NOT NULL DEFAULT false,
    validation_rules    JSONB,                              -- {min, max, pattern, customRule}
    default_value       TEXT,

    -- Search configuration
    is_searchable       BOOLEAN      NOT NULL DEFAULT false,
    is_filterable       BOOLEAN      NOT NULL DEFAULT false,
    is_sortable         BOOLEAN      NOT NULL DEFAULT false,
    is_facetable        BOOLEAN      NOT NULL DEFAULT false,
    is_promoted         BOOLEAN      NOT NULL DEFAULT false, -- extracted to listing table dedicated column
    is_range_filterable BOOLEAN      NOT NULL DEFAULT false, -- rendered as range slider in consumer search

    -- Array sub-entity configuration (only for ARRAY_OF_OBJECTS)
    is_array_manageable BOOLEAN      NOT NULL DEFAULT false, -- generate add/update/delete endpoints

    -- Misc
    config              JSONB,
    is_active           BOOLEAN      NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_field_name_org UNIQUE (name, org_id)
);

CREATE INDEX idx_field_org  ON field_definition (org_id);
CREATE INDEX idx_field_type ON field_definition (field_type);

-- ── Field Groups (was: entity_definition) ────────────────────────────────────
-- A reusable, named collection of fields. Sections of a ListingType reference FieldGroups.

CREATE TABLE field_group (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID,
    name        VARCHAR(100) NOT NULL,
    label       VARCHAR(200) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_field_group_name_org UNIQUE (name, org_id)
);

CREATE INDEX idx_field_group_org ON field_group (org_id);

-- ── Field Group Entries (was: entity_attribute) ───────────────────────────────
-- Join table: which fields belong to a FieldGroup, in what order, with what overrides.

CREATE TABLE field_group_entry (
    field_group_id  UUID    NOT NULL REFERENCES field_group(id) ON DELETE CASCADE,
    field_id        UUID    NOT NULL REFERENCES field_definition(id),
    display_order   INT     NOT NULL DEFAULT 0,
    is_required     BOOLEAN NOT NULL DEFAULT false,   -- can override field_definition.is_required
    PRIMARY KEY (field_group_id, field_id)
);

-- ── Listing Type Definitions (was: object_type_definition) ───────────────────
-- Top-level definition of a vendor listing type (VENUE, PHOTOGRAPHER, etc.)
-- or any other managed entity type (VENDOR, WEDDING_EVENT, CORPORATE_EVENT).

CREATE TABLE listing_type_definition (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID,                           -- NULL = platform-level
    name                    VARCHAR(100) NOT NULL,          -- unique key, e.g. "VENUE"
    label                   VARCHAR(200) NOT NULL,
    description             TEXT,
    icon                    VARCHAR(100),
    color                   VARCHAR(20),

    -- Lifecycle flags
    is_publishable          BOOLEAN      NOT NULL DEFAULT false, -- has consumer-facing snapshots
    is_consumer_searchable  BOOLEAN      NOT NULL DEFAULT false, -- included in public-{type} OpenSearch index
    is_active               BOOLEAN      NOT NULL DEFAULT true,

    -- Versioning: current published schema version
    current_version         INTEGER      NOT NULL DEFAULT 0,

    config                  JSONB,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_listing_type_name_org UNIQUE (name, org_id)
);

CREATE INDEX idx_listing_type_org ON listing_type_definition (org_id);

-- ── Listing Type Sections (was: object_type_section) ─────────────────────────
-- Ordered sections of a listing type, each backed by a FieldGroup.

CREATE TABLE listing_type_section (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_type_id UUID         NOT NULL REFERENCES listing_type_definition(id) ON DELETE CASCADE,
    field_group_id  UUID         NOT NULL REFERENCES field_group(id),
    label           VARCHAR(200),                           -- display label override for this section
    section_key     VARCHAR(100) NOT NULL,                  -- URL-safe key used in PATCH /sections/{key}
    display_order   INT          NOT NULL DEFAULT 0,
    is_collapsible  BOOLEAN      NOT NULL DEFAULT false,

    CONSTRAINT uq_section_key_type UNIQUE (listing_type_id, section_key)
);

CREATE INDEX idx_section_listing_type ON listing_type_section (listing_type_id);

-- ── Schema Versions ───────────────────────────────────────────────────────────
-- Immutable snapshot of a listing type schema at each publish point.

CREATE TABLE schema_version (
    id                    UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_type          VARCHAR(100) NOT NULL,
    version               INTEGER      NOT NULL,
    schema_snapshot       JSONB,                            -- full schema as JSON at publish time
    change_classification VARCHAR(20)  NOT NULL DEFAULT 'SAFE', -- SAFE | SOFT_BREAKING | HARD_BREAKING
    change_summary        TEXT,
    published_by          VARCHAR(255),
    published_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_schema_version_type_ver UNIQUE (listing_type, version)
);

CREATE INDEX idx_schema_version_type ON schema_version (listing_type, version DESC);

-- ── Tier Eligibility Rules ────────────────────────────────────────────────────
-- Defines what a vendor must satisfy before being upgraded to a verification tier.
-- Evaluated by schema-registry before RecordVerificationService.set() persists the tier.

CREATE TABLE tier_eligibility_rule (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_type    VARCHAR(100) NOT NULL,                  -- e.g. "VENDOR"
    tier            VARCHAR(30)  NOT NULL,                  -- BASIC | ENHANCED | PREMIUM
    rule_type       VARCHAR(30)  NOT NULL,                  -- DOCUMENT_VERIFIED | FIELD_CONDITION | MIN_BOOKINGS
    document_code   VARCHAR(100),                           -- for DOCUMENT_VERIFIED
    field_path      VARCHAR(200),                           -- for FIELD_CONDITION, e.g. "taxInfo.gstStatus"
    operator        VARCHAR(10),                            -- EQ | NEQ | GTE | LTE | IN | NOT_NULL
    value           VARCHAR(200),                           -- for FIELD_CONDITION
    min_count       INTEGER,                                -- for MIN_BOOKINGS
    force_overridable BOOLEAN    NOT NULL DEFAULT true,     -- admin can bypass with forceOverride flag
    display_name    VARCHAR(200) NOT NULL,
    description     VARCHAR(500),
    display_order   INTEGER      NOT NULL DEFAULT 0,
    is_active       BOOLEAN      NOT NULL DEFAULT true,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_tier_rule_type_tier ON tier_eligibility_rule (listing_type, tier);

-- ── Search Definitions ────────────────────────────────────────────────────────
-- Per-listing-type search configuration consumed by search-service.

CREATE TABLE search_definition (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    listing_type        VARCHAR(100) NOT NULL UNIQUE,
    consumer_facets     JSONB,                              -- string[] of field keys for consumer facets
    admin_facets        JSONB,                              -- string[] of field keys for admin facets
    default_sort_field  VARCHAR(100),
    default_sort_dir    VARCHAR(5)   NOT NULL DEFAULT 'ASC',
    boost_field         VARCHAR(100),                       -- field whose value is used for ranking boost
    is_active           BOOLEAN      NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

-- ── Relationship Definitions ──────────────────────────────────────────────────
-- Directed named relationships between listing types.

CREATE TABLE relationship_definition (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID,
    name                VARCHAR(100) NOT NULL,
    label               VARCHAR(200) NOT NULL,
    source_type         VARCHAR(100) NOT NULL,
    target_type         VARCHAR(100) NOT NULL,
    relationship_type   VARCHAR(50)  NOT NULL,              -- ONE_TO_ONE | ONE_TO_MANY | MANY_TO_MANY | PARENT_CHILD
    is_required         BOOLEAN      NOT NULL DEFAULT false,
    cascade_delete      BOOLEAN      NOT NULL DEFAULT false,
    is_active           BOOLEAN      NOT NULL DEFAULT true,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now()
);

CREATE INDEX idx_rel_def_org         ON relationship_definition (org_id);
CREATE INDEX idx_rel_def_source_type ON relationship_definition (source_type);

-- ── Category Definitions ──────────────────────────────────────────────────────
-- Hierarchical taxonomy for listing types.

CREATE TABLE category_definition (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id        UUID,
    parent_id     UUID         REFERENCES category_definition(id) ON DELETE SET NULL,
    listing_type  VARCHAR(100),
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

CREATE INDEX idx_category_parent      ON category_definition (parent_id);
CREATE INDEX idx_category_listing_type ON category_definition (listing_type);
CREATE INDEX idx_category_org         ON category_definition (org_id);

-- ── Document Requirements (was: document_type_definition) ────────────────────
-- Document types required for onboarding, with per-tier requirements.

CREATE TABLE document_requirement (
    id                  UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id              UUID,
    listing_type        VARCHAR(100),
    code                VARCHAR(100) NOT NULL,
    label               VARCHAR(200) NOT NULL,
    description         TEXT,

    -- Simple required flag (required regardless of tier)
    is_required         BOOLEAN      NOT NULL DEFAULT false,
    -- Tier-specific: array of tier names that require this doc, e.g. ["BASIC","PREMIUM"]
    required_for_tiers  JSONB,

    expiry_tracked      BOOLEAN      NOT NULL DEFAULT false,
    allowed_mime_types  JSONB,
    max_size_mb         INT          NOT NULL DEFAULT 5,
    is_active           BOOLEAN      NOT NULL DEFAULT true,
    display_order       INT          NOT NULL DEFAULT 0,
    created_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at          TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_doc_req_code_org UNIQUE (code, org_id)
);

CREATE INDEX idx_doc_req_org          ON document_requirement (org_id);
CREATE INDEX idx_doc_req_listing_type ON document_requirement (listing_type);

-- ── Tier Configurations ───────────────────────────────────────────────────────
-- Business parameters per tier per listing type.

CREATE TABLE tier_configuration (
    id                      UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    tier_name               VARCHAR(30)  NOT NULL,
    listing_type            VARCHAR(100),                   -- NULL = applies to all types
    commission_rate         DECIMAL(5,2) NOT NULL DEFAULT 20.00,
    max_active_bookings     INT,
    search_boost_factor     DECIMAL(4,2) NOT NULL DEFAULT 1.00,
    response_sla_hours      INT          NOT NULL DEFAULT 48,
    expiry_days             INT          NOT NULL DEFAULT 0, -- 0 = no expiry
    features                JSONB,
    is_active               BOOLEAN      NOT NULL DEFAULT true,
    created_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_tier_name_listing_type UNIQUE (tier_name, listing_type)
);

CREATE INDEX idx_tier_config_name ON tier_configuration (tier_name);

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
