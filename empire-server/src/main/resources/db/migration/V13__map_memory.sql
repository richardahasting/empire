-- Issue #64: a per-country map of everything ever seen. The fog closes behind a ship, but the chart
-- does not blank — a coastline you sailed past stays drawn, as it was when you saw it.
--
-- Only what a look would tell you: terrain, whose flag was over it, what it was built as. Never stock,
-- never mobility, never roads. One row per (country, sector) — the newest look replaces the old one.
CREATE TABLE sector_seen (
    game_id      BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    owner        INT NOT NULL,
    x            INT NOT NULL,
    y            INT NOT NULL,
    terrain      TEXT NOT NULL,
    sector_owner INT NOT NULL,
    designation  TEXT NOT NULL,
    seen_update  BIGINT NOT NULL,
    PRIMARY KEY (game_id, owner, x, y)
);
