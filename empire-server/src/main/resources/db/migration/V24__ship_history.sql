-- A ship's logbook (issue #67): per update, what each ship did, a line at a time. {"12": ["loaded 350 lcm at 4,5", ...]}
ALTER TABLE update_log ADD COLUMN ship_notes JSONB NOT NULL DEFAULT '{}'::jsonb;
