ALTER TABLE game ADD COLUMN interval_seconds BIGINT NOT NULL DEFAULT 0;   -- 0 = manual updates only
ALTER TABLE game ADD COLUMN next_update_at TIMESTAMPTZ;
