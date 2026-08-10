-- The seeded "gallery" field was typed MULTI_SELECT, which is an enum picker: it validates
-- values against enum_values and has no notion of an uploaded object. Nothing could ever be
-- put in it — record-service's upload flow writes a single key per field, so the "Gallery &
-- Media" section of every listing type was unreachable while the consumer card configuration
-- already pointed at a gallery endpoint.
--
-- MEDIA_GALLERY is the real type: an ordered array of {id, key, caption, isPrimary} written
-- only by record-service's gallery endpoints.
--
-- The seeder creates fields only when absent, so it cannot correct an existing row. This can.
UPDATE field_definition
   SET field_type = 'MEDIA_GALLERY',
       -- enum_values was never populated for this field, but clear it so nothing later reads
       -- an option list off a field that has no options.
       enum_values = NULL,
       updated_at  = now()
 WHERE name       = 'gallery'
   AND field_type = 'MULTI_SELECT';

-- Record data needs no migration. The field never had a write path, so there are no gallery
-- values to convert; and GalleryItem.listFrom skips entries that are not objects, so a stray
-- MULTI_SELECT array of strings degrades to an empty gallery rather than failing the read.
