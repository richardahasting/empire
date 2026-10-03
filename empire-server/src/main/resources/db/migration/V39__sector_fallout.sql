-- Fallout (issue #71; KNOWN sct_fallout): radiation a detonation left, 0..9999, which melts what is there and spreads.
ALTER TABLE sector ADD COLUMN fallout INT NOT NULL DEFAULT 0;
