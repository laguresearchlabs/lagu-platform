-- Event table: one row per event (birthday, wedding, ...). Unlike vendor-service (one type
-- per org forever), an event's org_id is a throwaway, internal-only partition key minted at
-- creation purely so record-service's per-record org_id NOT NULL constraint is satisfied — it
-- is never written back to the user's IAM platformOrgId (a user must belong to many events
-- simultaneously, which IAM's single-org-per-user model can't represent).
CREATE TABLE IF NOT EXISTS event (
    id              UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id          UUID        NOT NULL UNIQUE,    -- internal record-service partition key
    record_id       UUID        NOT NULL UNIQUE,    -- corresponding record in record-service
    object_type     VARCHAR(60) NOT NULL,           -- BIRTHDAY_EVENT | WEDDING_EVENT | ...
    owner_user_id   UUID        NOT NULL,           -- IAM userId of the creator
    status          VARCHAR(30) NOT NULL DEFAULT 'PLANNING',
    version         BIGINT      NOT NULL DEFAULT 0,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at      TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX IF NOT EXISTS idx_event_owner ON event (owner_user_id);
CREATE INDEX IF NOT EXISTS idx_event_object_type ON event (object_type);

-- Event members: authorization is enforced entirely here (role/status), never via IAM org
-- membership — event-service is the sole caller of record-service for an event's underlying
-- record, always as SVC_EVENT_SERVICE, after checking this table itself.
CREATE TABLE IF NOT EXISTS event_member (
    id          UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id      UUID        NOT NULL,               -- = event.org_id
    user_id     UUID        NOT NULL,
    role        VARCHAR(30) NOT NULL DEFAULT 'INVITEE',   -- ADMIN | MAINTAINER | INVITEE
    status      VARCHAR(30) NOT NULL DEFAULT 'ACCEPTED',  -- INVITED | ACCEPTED | DECLINED
    guest_note  VARCHAR(500),
    muted       BOOLEAN     NOT NULL DEFAULT FALSE,
    invited_by  UUID,
    joined_at   TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_event_member_org ON event_member (org_id);
CREATE INDEX IF NOT EXISTS idx_event_member_user ON event_member (user_id);

-- Requests to join an event (e.g. via a share link) awaiting ADMIN approval.
CREATE TABLE IF NOT EXISTS event_join_request (
    id                      UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id                  UUID        NOT NULL,   -- = event.org_id
    user_id                 UUID        NOT NULL,
    requested_role          VARCHAR(30) NOT NULL DEFAULT 'INVITEE',
    status                  VARCHAR(30) NOT NULL DEFAULT 'PENDING', -- PENDING | APPROVED | REJECTED
    message                 VARCHAR(500),
    reviewed_by_user_id     UUID,
    reviewed_at             TIMESTAMPTZ,
    created_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    updated_at              TIMESTAMPTZ NOT NULL DEFAULT now(),
    UNIQUE (org_id, user_id)
);

CREATE INDEX IF NOT EXISTS idx_event_join_request_org ON event_join_request (org_id);
