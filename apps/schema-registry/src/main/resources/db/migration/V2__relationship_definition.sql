-- =============================================================================
-- Schema Registry — V2 Relationship Definitions
-- Ported from metadata-service.relationship_definition, which V1 missed.
-- Defines how listing types relate to each other (e.g. an event references a
-- venue, photographers, caterers).
-- =============================================================================

CREATE TABLE relationship_definition (
    id                   UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id               UUID,
    name                 VARCHAR(100) NOT NULL,
    label                VARCHAR(200) NOT NULL,
    source_listing_type  VARCHAR(100) NOT NULL,
    target_listing_type  VARCHAR(100) NOT NULL,
    relationship_type    VARCHAR(50)  NOT NULL,
    is_required          BOOLEAN      NOT NULL DEFAULT false,
    cascade_delete       BOOLEAN      NOT NULL DEFAULT false,
    is_active            BOOLEAN      NOT NULL DEFAULT true,
    created_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at           TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_rel_def_name_org UNIQUE NULLS NOT DISTINCT (name, org_id)
);

CREATE INDEX idx_rel_def_org ON relationship_definition (org_id);
