-- Dedup key for the notification produced by one AutomationEvent (identified by its eventId).
-- Nullable because a notification can also be created directly via the REST API with no
-- originating event; the unique index only applies to non-null values, so redelivering the same
-- Kafka message (same eventId) hits the constraint instead of inserting a duplicate row, while
-- multiple API-created notifications (all null) are unaffected.
ALTER TABLE notification.notification
    ADD COLUMN source_event_id UUID;

CREATE UNIQUE INDEX uq_notification_source_event_id
    ON notification.notification (source_event_id)
    WHERE source_event_id IS NOT NULL;
