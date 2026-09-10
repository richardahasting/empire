-- The per-update world hash is now optional (issue #82): nothing reads it, and computing it costs
-- about 9.5 seconds an update on a million-sector map. Null means "not computed", which is honest;
-- an empty string would look like a hash of nothing.
ALTER TABLE update_log ALTER COLUMN state_hash DROP NOT NULL;
