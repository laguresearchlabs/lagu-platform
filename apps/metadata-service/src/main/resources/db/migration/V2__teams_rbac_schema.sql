-- ── Teams Schema ─────────────────────────────────────────────────────────────

CREATE TABLE teams.group_definition (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID         NOT NULL,
    name        VARCHAR(100) NOT NULL,
    description TEXT,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_name_org UNIQUE (name, org_id)
);

CREATE TABLE teams.group_member (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    group_id    UUID         NOT NULL REFERENCES teams.group_definition(id) ON DELETE CASCADE,
    org_id      UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    role_name   VARCHAR(100),
    joined_at   TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_group_member UNIQUE (group_id, user_id)
);

CREATE INDEX idx_group_org    ON teams.group_definition (org_id);
CREATE INDEX idx_member_user  ON teams.group_member (org_id, user_id);
CREATE INDEX idx_member_group ON teams.group_member (group_id);

-- ── RBAC Schema (stored in metadata schema) ───────────────────────────────────

CREATE TABLE metadata.role_definition (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID,
    name        VARCHAR(100) NOT NULL,
    label       VARCHAR(200) NOT NULL,
    description TEXT,
    role_level  VARCHAR(20)  NOT NULL,
    is_active   BOOLEAN      NOT NULL DEFAULT true,
    created_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    -- NULLS NOT DISTINCT: two platform-level roles (org_id = NULL) with same name conflict
    CONSTRAINT uq_role_name_org UNIQUE NULLS NOT DISTINCT (name, org_id)
);

CREATE TABLE metadata.permission_definition (
    id            UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    resource_type VARCHAR(100) NOT NULL,
    action        VARCHAR(50)  NOT NULL,
    role_id       UUID         NOT NULL REFERENCES metadata.role_definition(id) ON DELETE CASCADE,
    conditions    JSONB,
    created_at    TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_perm_resource_action_role UNIQUE (resource_type, action, role_id)
);

CREATE TABLE metadata.user_role (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID         NOT NULL,
    user_id     UUID         NOT NULL,
    role_id     UUID         NOT NULL REFERENCES metadata.role_definition(id),
    granted_by  UUID,
    granted_at  TIMESTAMPTZ  NOT NULL DEFAULT now(),
    CONSTRAINT uq_user_role_org UNIQUE (org_id, user_id, role_id)
);

CREATE INDEX idx_role_org      ON metadata.role_definition (org_id);
CREATE INDEX idx_perm_role     ON metadata.permission_definition (role_id);
CREATE INDEX idx_user_role_org ON metadata.user_role (org_id, user_id);

-- ── Seed platform-level roles ─────────────────────────────────────────────────
-- These are immutable (org_id IS NULL) and inserted once.

INSERT INTO metadata.role_definition (name, label, description, role_level)
VALUES
    ('PLATFORM_ADMIN', 'Platform Administrator', 'Full access to all orgs and configuration', 'PLATFORM'),
    ('CONFIG_ADMIN',   'Configuration Administrator', 'Manage metadata definitions for their org', 'PLATFORM'),
    ('ORG_USER',       'Organisation User', 'Standard authenticated user in an org', 'PLATFORM'),
    ('ORG_OWNER',      'Organisation Owner', 'Owns the organisation', 'BUSINESS'),
    ('ORG_MANAGER',    'Organisation Manager', 'Manages resources within the org', 'BUSINESS'),
    ('ORG_STAFF',      'Organisation Staff', 'Operates within the org', 'BUSINESS')
ON CONFLICT (name, org_id) DO NOTHING;
