-- Event photo album, replacing image-service's group/sub-group model.
--
-- Posts did not need this: they are EVENT_POST *records*, so retyping their image field to
-- MEDIA_GALLERY handed them record-service's gallery endpoints. An Event is an event-service
-- entity with no record behind it and no image field at all — every event photo lived in
-- image-service keyed by event id, with nothing stored here — so it needs its own table.

CREATE TABLE event_photo (
    id              UUID         PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID         NOT NULL REFERENCES event(id) ON DELETE CASCADE,
    -- ON DELETE CASCADE so deleting an event cannot leave rows pointing at it. The storage
    -- objects are swept separately: the row going away is what makes them unreferenced, and the
    -- bucket lifecycle rule is not scoped to reach confirmed objects, so EventPhotoService
    -- deletes them explicitly while it still knows the keys.

    -- Storage keys, never URLs. A signed URL lasts minutes; these last as long as the photo.
    storage_key     VARCHAR(1024) NOT NULL,
    card_key        VARCHAR(1024),
    full_key        VARCHAR(1024),
    -- card/full are the derivatives MediaIngest builds. Nullable because a format the platform
    -- cannot decode (HEIC, anything exotic) still uploads fine, it just has no thumbnail.

    -- PUBLIC photos appear on the event overview to every member; PRIVATE are for managers.
    -- The overview widget is shown to all viewers, so this column is what keeps a private photo
    -- out of it — not a filter the caller is trusted to pass.
    visibility      VARCHAR(20)  NOT NULL DEFAULT 'PUBLIC',
    caption         VARCHAR(300),
    uploaded_by     UUID,
    created_at      TIMESTAMPTZ  NOT NULL DEFAULT now(),

    CONSTRAINT uq_event_photo_key UNIQUE (storage_key),
    CONSTRAINT ck_event_photo_visibility CHECK (visibility IN ('PUBLIC', 'PRIVATE'))
);

-- The album query: one event's photos of one visibility, newest first.
CREATE INDEX idx_event_photo_event_visibility
    ON event_photo (event_id, visibility, created_at DESC);
