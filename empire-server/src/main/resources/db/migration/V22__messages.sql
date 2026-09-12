-- Issue #140: countries talking to each other. A telegram goes to one country, an announcement to
-- everyone (to_country null).
--
-- Not world state, on purpose. The update is a pure function of the world, and a state hash must not
-- move because somebody sent a letter — so the engine validates and charges for a message and the
-- server keeps it. Nothing in an update ever reads this table.
--
-- from_country and to_country are ids, never names: names change (#119), and a message crediting a
-- name nobody recognises is the same bug the news feed avoided.
CREATE TABLE message (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    update_number BIGINT NOT NULL,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    from_country  INT NOT NULL,
    to_country    INT,                  -- null: an announcement, to everybody
    body          TEXT NOT NULL
);

CREATE INDEX message_game ON message(game_id, id);

-- How far each country has read its post, the same watermark shape the news feed uses. Keyed by
-- country rather than account, because the post belongs to the country and a seat can change hands.
CREATE TABLE message_seen (
    game_id     BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    country_id  INT NOT NULL,
    last_seen   BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (game_id, country_id)
);
