-- Per-account macros (issue #47): up to ten slots of recorded panel actions with the sector left blank.
CREATE TABLE macro (
    account_id  BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    slot        INT NOT NULL CHECK (slot BETWEEN 1 AND 10),
    name        TEXT NOT NULL,
    steps       JSONB NOT NULL,
    updated_at  TIMESTAMPTZ NOT NULL DEFAULT now(),
    PRIMARY KEY (account_id, slot)
);
