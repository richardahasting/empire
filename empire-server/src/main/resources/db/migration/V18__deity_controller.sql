-- Issue #128: POGO, the deity's own country. Which country a deity plays is the same kind of fact
-- as which country an agent plays — server-side bookkeeping, never world state — so it lives in the
-- controller column beside 'human' and 'agent' rather than in the engine's Country record.
ALTER TABLE country DROP CONSTRAINT IF EXISTS country_controller_check;
ALTER TABLE country ADD CONSTRAINT country_controller_check
    CHECK (controller IN ('human', 'agent', 'none', 'deity'));

-- Deity edits are writes outside the update, so they cost the game two guarantees: a commodity's
-- total no longer has to equal what was produced and consumed, and a world no longer replays from
-- (config, seed, commands). That is a fair price for a tool whose purpose is rescuing a broken game
-- — but never a silent one, so every edit is recorded here and a later "why will this not reproduce"
-- has an answer.
CREATE TABLE deity_edit (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    update_number BIGINT NOT NULL,
    target        TEXT NOT NULL,          -- "sector 3,4" or "country 2 (Ruritania)"
    changes       TEXT NOT NULL,          -- what was set, and what it was before
    account_id    BIGINT REFERENCES account(id),
    at            TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE INDEX deity_edit_game ON deity_edit(game_id, id);
