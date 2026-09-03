-- Inviting somebody who does not have an account yet.
--
-- `event_member` keys on user_id, so an invitation could only ever be addressed to a user this
-- platform already knows. A host planning a birthday knows their guests by phone number and
-- email, not by whether those people have signed up — so the invite flow could reach almost
-- nobody, and the answer was "send them a share link and hope".
--
-- A pending invitation is deliberately NOT a member row with a null user_id. Every read path in
-- this service resolves membership by user id (EventMembershipGuard, the posts feed, the photo
-- album), and a nullable key would make each of those quietly answerable for a person who does
-- not exist. It is a separate thing that BECOMES a membership when it is claimed.

CREATE TABLE event_invitation (
    id              UUID          PRIMARY KEY DEFAULT gen_random_uuid(),
    tenant_id       UUID          NOT NULL,
    event_id        UUID          NOT NULL REFERENCES event(id) ON DELETE CASCADE,

    -- Exactly one of these identifies the invitee. Normalised on the way in — lowercased email,
    -- digits-only phone — because the claim is an equality match against whatever the identity
    -- service holds, and "Sam@Example.com " would never find it.
    email           VARCHAR(320),
    phone           VARCHAR(20),
    country_code    VARCHAR(8),

    -- What they get on claiming. Same constraint as the share link's, and for the same reason:
    -- nothing that mints an ADMIN may be reachable from a form.
    role            VARCHAR(30)   NOT NULL DEFAULT 'INVITEE',

    -- PENDING until claimed or revoked. Rows are marked, never deleted: "who did we invite, and
    -- did they ever come" is most of what this table is for.
    status          VARCHAR(30)   NOT NULL DEFAULT 'PENDING',

    invited_by      UUID          NOT NULL,
    created_at      TIMESTAMPTZ   NOT NULL DEFAULT now(),

    claimed_by      UUID,
    claimed_at      TIMESTAMPTZ,
    revoked_by      UUID,
    revoked_at      TIMESTAMPTZ,

    CONSTRAINT ck_invitation_role   CHECK (role IN ('INVITEE', 'MAINTAINER')),
    CONSTRAINT ck_invitation_status CHECK (status IN ('PENDING', 'CLAIMED', 'REVOKED')),

    -- One contact or the other, never both and never neither. Without this the claim lookup has
    -- no well-defined key and a row can be addressed to nobody.
    CONSTRAINT ck_invitation_contact CHECK (
        (email IS NOT NULL AND phone IS NULL) OR (email IS NULL AND phone IS NOT NULL)
    )
);

-- The host's list for one event, newest first.
CREATE INDEX idx_invitation_event ON event_invitation (event_id, created_at DESC);

-- The claim path: "what is waiting for this contact". Partial, because a claimed or revoked row
-- is never a candidate and there are far more of those over time than pending ones.
CREATE INDEX idx_invitation_pending_email ON event_invitation (email)
    WHERE status = 'PENDING' AND email IS NOT NULL;
CREATE INDEX idx_invitation_pending_phone ON event_invitation (phone)
    WHERE status = 'PENDING' AND phone IS NOT NULL;

-- One live invitation per contact per event. A host clicking Invite twice should not produce two
-- rows that both become memberships, and the People tab should not show the same person twice.
CREATE UNIQUE INDEX uq_invitation_pending_email ON event_invitation (event_id, email)
    WHERE status = 'PENDING' AND email IS NOT NULL;
CREATE UNIQUE INDEX uq_invitation_pending_phone ON event_invitation (event_id, phone)
    WHERE status = 'PENDING' AND phone IS NOT NULL;

-- Which invitation admitted someone, mirroring joined_via_share_link_id. Answers "did this
-- person come from the list I sent" long after the fact, and survives the invitation being
-- superseded. SET NULL rather than CASCADE: the membership is real and outlives its invitation.
ALTER TABLE event_member
    ADD COLUMN joined_via_invitation_id UUID REFERENCES event_invitation(id) ON DELETE SET NULL;
