-- Per-sector plain-language notes for each update (issue #49): {"x,y": ["made 430 lcm using 430 iron", ...]}
ALTER TABLE update_log ADD COLUMN notes JSONB NOT NULL DEFAULT '{}'::jsonb;
