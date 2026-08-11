-- Event post attachments move off image-service and onto the platform's own media pipeline.
--
-- `post_image_ids` was a JSON field holding image-service UUIDs, which is what tied event posts
-- to a service the platform no longer needs. Posts are EVENT_POST *records*, so retyping the
-- field to MEDIA_GALLERY gives them record-service's gallery endpoints wholesale — presigned
-- upload, malware scanning, dimension rules, derivatives, ordering, captions and per-request
-- signing — without a line of media code in event-service.
--
-- Renamed as well as retyped: the values are storage keys now, not ids, and a field still called
-- *_image_ids holding keys is the kind of thing that costs someone an afternoon later.
--
-- The seeder only creates fields that are absent, so it cannot correct the existing row.
UPDATE field_definition
   SET name       = 'post_images',
       label      = 'Images',
       field_type = 'MEDIA_GALLERY',
       updated_at = now()
 WHERE name       = 'post_image_ids';

-- Field group entries reference the field by id, so the rename above carries through and the
-- "Post" group needs no change.

-- Existing post_image_ids VALUES in record data are deliberately not migrated. They are
-- image-service UUIDs, not storage keys, and there is nothing to translate them into: the
-- objects live in another service's bucket under a different addressing scheme. GalleryItem
-- .listFrom skips entries that are not objects, so any leftover array of UUIDs reads as an empty
-- gallery rather than failing. Pre-launch, that is the whole of the migration.
