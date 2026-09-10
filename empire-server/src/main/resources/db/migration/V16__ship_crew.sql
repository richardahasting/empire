-- Issue #66: a hull needs people aboard to sail. Like fuel, a crew is not cargo — it does not eat into
-- the hold and it is not unloaded with the catch — so it gets its own column.
ALTER TABLE ship ADD COLUMN crew DOUBLE PRECISION NOT NULL DEFAULT 0;
