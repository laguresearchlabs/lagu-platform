-- Defect 7: a field-group entry could only ever ADD requiredness, never relax it.
--
-- Requiredness resolved as `field.isRequired() || entry.isRequired()`, so unchecking "required" on
-- a placement did nothing when the field definition said required. The admin UI presents that
-- control as an override, so it silently lied — and it is what made the first attempt at defect 2
-- ineffective.
--
-- Making the entry win needs three states, not two. A plain boolean cannot distinguish "this
-- placement says optional" from "this placement has no opinion", and today every entry that was
-- simply never touched sits at false. Reading those as an explicit "optional" would un-require
-- every globally-required field on the platform in one deploy.
--
--   NULL  -> inherit field_definition.is_required   (the default, and what untouched rows mean)
--   true  -> required here regardless of the definition
--   false -> optional here regardless of the definition   (newly expressible)

ALTER TABLE field_group_entry
    ADD COLUMN required_override BOOLEAN;

-- Carry the only opinion the old column could actually express. `is_required = true` was a real
-- "make this required here"; `is_required = false` was the absence of an opinion, so it becomes
-- NULL rather than an explicit relaxation. This preserves today's resolved requiredness exactly:
-- every field keeps the requiredness it had before this migration.
UPDATE field_group_entry
   SET required_override = true
 WHERE is_required = true;

-- is_required is deliberately left in place rather than dropped. It is still written by older
-- images that may be mid-rollout, and dropping a NOT NULL column under a running deployment turns
-- a rolling restart into an outage. It is no longer read — see ListingTypeService — and can be
-- dropped in a later migration once every service is past this version.
COMMENT ON COLUMN field_group_entry.is_required IS
    'Superseded by required_override (V7). No longer read; kept for rollback safety.';
COMMENT ON COLUMN field_group_entry.required_override IS
    'NULL = inherit field_definition.is_required; true/false = this placement overrides it.';
