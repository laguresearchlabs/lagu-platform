-- Per-user notification preferences.
--
-- Deliberately NOT tenant-scoped, unlike notification.notification: a preference belongs to a
-- person, not to an organisation. Someone who opts out of marketing means it everywhere, not
-- per tenant they happen to belong to.
--
-- Rows are sparse. An absent (user_id, category) row means "platform default" — see
-- NotificationPreferenceService.DEFAULTS. Do not backfill; a user who never opens the
-- preferences screen should have no rows.
CREATE TABLE notification.user_notification_preference (
    id          UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    user_id     UUID         NOT NULL,
    category    VARCHAR(40)  NOT NULL,
    in_app      BOOLEAN      NOT NULL DEFAULT TRUE,
    email       BOOLEAN      NOT NULL DEFAULT TRUE,
    updated_at  TIMESTAMPTZ  NOT NULL DEFAULT NOW(),
    CONSTRAINT uq_user_notification_preference UNIQUE (user_id, category)
);

CREATE INDEX idx_user_notification_preference_user
    ON notification.user_notification_preference (user_id);
