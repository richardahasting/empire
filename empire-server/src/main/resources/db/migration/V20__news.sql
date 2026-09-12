-- Issue #121: what happened in the world since you last looked.
--
-- Separate from update_log.events because news happens both inside updates (milestones) and outside
-- them (somebody joined, somebody renamed), and because every existing event is one country's
-- private misfortune — there has never been an event anyone else was allowed to see.
--
-- country_id, never a name: names change (#119), and an item announcing a rename would otherwise be
-- the first thing to go stale. CountryView already resolves names at read time for the same reason.
CREATE TABLE news (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    update_number BIGINT NOT NULL,
    at            TIMESTAMPTZ NOT NULL DEFAULT now(),
    type          TEXT NOT NULL,          -- joined | renamed | started | milestone
    country_id    INT,                    -- who it is about; null for news about the game itself
    message       TEXT NOT NULL,          -- written with a {country} placeholder, filled in on read
    -- set for anything that may happen only once in a game, e.g. "first:refinery". The unique index
    -- below is what makes a milestone one-shot without anyone having to remember it was claimed.
    one_shot_key  TEXT
);

CREATE UNIQUE INDEX news_one_shot ON news(game_id, one_shot_key) WHERE one_shot_key IS NOT NULL;
CREATE INDEX news_game ON news(game_id, id);

-- How far each person has read, so "since you last looked" means something. Without it the feed
-- either repeats itself forever or shows nothing.
CREATE TABLE news_seen (
    account_id  BIGINT NOT NULL REFERENCES account(id) ON DELETE CASCADE,
    game_id     BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    last_seen   BIGINT NOT NULL DEFAULT 0,
    PRIMARY KEY (account_id, game_id)
);
