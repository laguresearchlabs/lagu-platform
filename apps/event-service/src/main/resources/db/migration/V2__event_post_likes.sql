-- Post likes: the one piece of the social-feed feature that doesn't fit a Record, since a
-- "like" is a User<->Record edge and User isn't a Record on this platform. A small dedicated
-- toggle table, scoped by the post's record id (not org_id — likes aren't event-service's own
-- tenancy concern, just a per-post counter).
CREATE TABLE IF NOT EXISTS event_post_like (
    post_record_id  UUID        NOT NULL,
    user_id         UUID        NOT NULL,
    created_at      TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (post_record_id, user_id)
);
