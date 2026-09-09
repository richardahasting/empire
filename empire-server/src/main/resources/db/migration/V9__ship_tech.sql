-- A ship keeps the tech level it was laid at; speed scales with it (KNOWN: the original's ship tech).
ALTER TABLE ship ADD COLUMN tech DOUBLE PRECISION NOT NULL DEFAULT 0;
