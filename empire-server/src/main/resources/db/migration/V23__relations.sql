-- Issue #137: who is at war with whom.
--
-- One row per PAIR, always with a < b, so the state is mutual by construction rather than by two
-- rows agreeing. A one-sided war where the victim's ships do not shoot back is not a war, and making
-- mutuality structural means no code can get it half right.
--
-- Absent means peace: a game with no rows here is a game where nobody has fallen out.
--
-- This is world state, unlike messages (#140). Combat happens in the update and the update is a pure
-- function of the world, so who is at war has to be in the world or the update cannot see it.
CREATE TABLE relation (
    game_id          BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    a                INT NOT NULL,
    b                INT NOT NULL,
    state            TEXT NOT NULL CHECK (state IN ('peace', 'war')),
    since_update     BIGINT NOT NULL,
    peace_offered_by INT,
    PRIMARY KEY (game_id, a, b),
    CHECK (a < b)
);
