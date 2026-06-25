CREATE TABLE attribute_definition (
    id               UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id           UUID,
    name             VARCHAR(100) NOT NULL,
    label            VARCHAR(200) NOT NULL,
    description      TEXT,
    attribute_type   VARCHAR(50)  NOT NULL,
    is_required      BOOLEAN      NOT NULL DEFAULT false,
    is_searchable    BOOLEAN      NOT NULL DEFAULT false,
    is_filterable    BOOLEAN      NOT NULL DEFAULT false,
    is_sortable      BOOLEAN      NOT NULL DEFAULT false,
    is_facetable     BOOLEAN      NOT NULL DEFAULT false,
    is_unique        BOOLEAN      NOT NULL DEFAULT false,
    default_value    TEXT,
    validation_rules JSONB,
    enum_values      JSONB,
    config           JSONB,
    is_active        BOOLEAN      NOT NULL DEFAULT true,
    created_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at       TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_attr_name_org UNIQUE (name, org_id)
);

CREATE INDEX idx_attr_org  ON attribute_definition (org_id);
CREATE INDEX idx_attr_type ON attribute_definition (attribute_type);

-- ── Entity Definitions ────────────────────────────────────────────────────────

CREATE TABLE entity_definition (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID,
    name        VARCHAR(100) NOT NULL,
    label       VARCHAR(200) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_entity_name_org UNIQUE (name, org_id)
);

CREATE TABLE entity_attribute (
    entity_id     UUID    NOT NULL REFERENCES entity_definition(id) ON DELETE CASCADE,
    attribute_id  UUID    NOT NULL REFERENCES attribute_definition(id),
    display_order INT     NOT NULL DEFAULT 0,
    is_required   BOOLEAN NOT NULL DEFAULT false,
    PRIMARY KEY (entity_id, attribute_id)
);

CREATE INDEX idx_entity_org ON entity_definition (org_id);

-- ── Object Type Definitions ───────────────────────────────────────────────────

CREATE TABLE object_type_definition (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id         UUID,
    name           VARCHAR(100) NOT NULL,
    label          VARCHAR(200) NOT NULL,
    description    TEXT,
    icon           VARCHAR(100),
    color          VARCHAR(20),
    is_publishable BOOLEAN      NOT NULL DEFAULT false,
    is_active      BOOLEAN      NOT NULL DEFAULT true,
    config         JSONB,
    created_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at     TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_objtype_name_org UNIQUE (name, org_id)
);

CREATE TABLE object_type_section (
    id             UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    object_type_id UUID         NOT NULL REFERENCES object_type_definition(id) ON DELETE CASCADE,
    entity_id      UUID         NOT NULL REFERENCES entity_definition(id),
    label          VARCHAR(200),
    display_order  INT          NOT NULL DEFAULT 0,
    is_collapsible BOOLEAN      NOT NULL DEFAULT false
);

CREATE INDEX idx_objtype_org      ON object_type_definition (org_id);
CREATE INDEX idx_section_objtype  ON object_type_section (object_type_id);

-- ── Relationship Definitions (Phase 2 placeholder) ────────────────────────────

CREATE TABLE relationship_definition (
    id                 UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id             UUID,
    name               VARCHAR(100) NOT NULL,
    label              VARCHAR(200) NOT NULL,
    source_object_type VARCHAR(100) NOT NULL,
    target_object_type VARCHAR(100) NOT NULL,
    relationship_type  VARCHAR(50)  NOT NULL,
    is_required        BOOLEAN      NOT NULL DEFAULT false,
    cascade_delete     BOOLEAN      NOT NULL DEFAULT false,
    is_active          BOOLEAN      NOT NULL DEFAULT true,
    created_at         TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at         TIMESTAMPTZ  NOT NULL DEFAULT now()
);
