-- Who a section is for.
--
-- `visible_when` answers "given the other values on this record, does this section apply" — a
-- rule about data. It has never been able to say "this section is for hosts", which is a rule
-- about the reader, so every consumer enforced that with a hard-coded check in its own page
-- code. A budget section was therefore host-only in the UI that remembered and public in the
-- one that didn't.
--
-- GUEST is the default so every section seeded before this keeps behaving exactly as it did.
ALTER TABLE listing_type_section
    ADD COLUMN audience VARCHAR(20) NOT NULL DEFAULT 'GUEST';

-- Ordered from least to most privileged; a client resolves its reader to one rung and renders
-- the sections at or below it. PUBLIC is the widest: safe for anyone holding a share link.
ALTER TABLE listing_type_section
    ADD CONSTRAINT ck_section_audience CHECK (audience IN ('PUBLIC', 'GUEST', 'HOST'));
