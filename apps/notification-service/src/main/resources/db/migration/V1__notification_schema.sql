CREATE SCHEMA IF NOT EXISTS notification;

CREATE TABLE notification.notification (
    id                UUID        PRIMARY KEY DEFAULT gen_random_uuid(),
    org_id            UUID,
    recipient_user_id UUID,
    title             VARCHAR(255) NOT NULL,
    message           TEXT,
    channel           VARCHAR(20)  NOT NULL DEFAULT 'IN_APP',  -- IN_APP | EMAIL | BOTH
    record_id         UUID,
    object_type       VARCHAR(100),
    trigger_id        UUID,
    trigger_name      VARCHAR(255),
    is_read           BOOLEAN      NOT NULL DEFAULT FALSE,
    read_at           TIMESTAMPTZ,
    email_sent        BOOLEAN      NOT NULL DEFAULT FALSE,
    email_sent_at     TIMESTAMPTZ,
    created_at        TIMESTAMPTZ  NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_notification_recipient
    ON notification.notification (recipient_user_id, is_read, created_at DESC);

CREATE INDEX idx_notification_org
    ON notification.notification (org_id, created_at DESC);
