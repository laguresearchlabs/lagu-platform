-- Track who requested the transition that opened an approval, so the engine can
-- reject self-approval. Nullable: pre-existing instances have no requester recorded
-- and are exempt from the guard.
ALTER TABLE approval_instance ADD COLUMN requested_by UUID;
