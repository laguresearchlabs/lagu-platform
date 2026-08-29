-- Share links as capabilities.
--
-- Before this, a "share link" was the event id and granted nothing: access was decided entirely
-- by the record's `visibility` field, so there was no link to revoke and revoking one would have
-- changed nothing. Holding a live token is now what admits a non-member, which is what makes
-- revocation mean something.
--
-- Rows are marked, never deleted: "who shared this, and when did we shut it off" is the question
-- the table exists to answer.

CREATE TABLE event_share_link (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID          NOT NULL REFERENCES event(id) ON DELETE CASCADE,

    -- SHA-256 of the token, never the token. A dump of this table must not yield working links.
    -- Not BCrypt: this is a 160-bit CSPRNG secret with no dictionary to slow down, and every
    -- anonymous preview hit resolves one — a deliberately slow hash would be the wrong tool.
    token_hash      CHAR(64)      NOT NULL UNIQUE,

    -- What the holder gets. INVITEE only: a link must never mint a co-host, for the same reason
    -- the UI's role picker must never offer ADMIN. The CHECK is what keeps that true if someone
    -- later widens the column's callers without thinking it through.
    grants_role     VARCHAR(30)   NOT NULL DEFAULT 'INVITEE',

    -- TRUE: opening the link joins you. FALSE: it raises a join request, as today. This is the
    -- setting that decides whether sharing a private event is one step or five.
    auto_admit      BOOLEAN       NOT NULL DEFAULT TRUE,

    -- "family WhatsApp group" — so revoking months later is an informed choice rather than a
    -- guess between three identical rows.
    label           VARCHAR(120),

    created_by      UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    expires_at      TIMESTAMPTZ,            -- NULL = never expires
    max_uses        INTEGER,                -- NULL = unlimited
    use_count       INTEGER       NOT NULL DEFAULT 0,
    last_used_at    TIMESTAMPTZ,

    revoked_at      TIMESTAMPTZ,
    revoked_by      UUID,

    CONSTRAINT ck_share_link_role     CHECK (grants_role IN ('INVITEE')),
    CONSTRAINT ck_share_link_max_uses CHECK (max_uses IS NULL OR max_uses > 0)
);

-- The host's own list: one event's links, newest first. Token lookup rides the UNIQUE above.
CREATE INDEX idx_share_link_event ON event_share_link (event_id, created_at DESC);

-- Which link let someone in. Without it, "revoke this link and remove everyone it admitted" is
-- not answerable — and that is the second thing a host asks for after revocation. ON DELETE SET
-- NULL rather than CASCADE: the membership is real and outlives the link that created it.
ALTER TABLE event_member
    ADD COLUMN joined_via_share_link_id UUID REFERENCES event_share_link(id) ON DELETE SET NULL;

CREATE INDEX idx_event_member_share_link ON event_member (joined_via_share_link_id);
