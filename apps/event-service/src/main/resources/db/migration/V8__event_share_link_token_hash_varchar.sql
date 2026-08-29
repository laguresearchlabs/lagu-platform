-- token_hash was CHAR(64) in V7: a SHA-256 hex digest is exactly 64 characters, so the fixed
-- width read as honest typing. It is not worth the cost. Postgres stores bpchar and varchar
-- identically, CHAR only adds blank-padding semantics we never want, and the entity maps the
-- field as a plain String — which Hibernate expects as varchar(64), failing schema validation
-- at startup against the CHAR column.
--
-- Every other text column in this schema is VARCHAR. This makes token_hash agree.
ALTER TABLE event_share_link
    ALTER COLUMN token_hash TYPE VARCHAR(64);
