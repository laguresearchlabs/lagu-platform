-- Add unique constraint to relationship_definition (missed in V1)
ALTER TABLE metadata.relationship_definition
    ADD CONSTRAINT uq_rel_def_name_org UNIQUE NULLS NOT DISTINCT (name, org_id);

-- Seed platform-level relationship definitions
INSERT INTO metadata.relationship_definition (name, label, source_object_type, target_object_type, relationship_type, is_required, cascade_delete)
VALUES
    ('EVENT_VENUE',         'Event Venue',         'WEDDING_EVENT', 'VENUE',        'ONE_TO_ONE',   false, false),
    ('EVENT_PHOTOGRAPHERS', 'Event Photographers', 'WEDDING_EVENT', 'PHOTOGRAPHER', 'MANY_TO_MANY', false, false),
    ('EVENT_CATERERS',      'Event Caterers',       'WEDDING_EVENT', 'CATERER',      'MANY_TO_MANY', false, false),
    ('EVENT_DECORATORS',    'Event Decorators',     'WEDDING_EVENT', 'DECORATOR',    'MANY_TO_MANY', false, false),
    ('EVENT_MAKEUP_ARTISTS','Event Makeup Artists', 'WEDDING_EVENT', 'MAKEUP_ARTIST','MANY_TO_MANY', false, false)
ON CONFLICT (name, org_id) DO NOTHING;
