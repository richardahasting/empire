-- Issue #70: standing depot-to-depot rail runs. A one-off rail_order is consumed at the update; a lane
-- stands until it is cancelled, and every update sends what the far end needs and this end can spare.
--
-- No cargo rows for a lane means "keep the destination's thresholds topped up" — that is what turns a
-- depot from a permit to run a train into a distribution link.
CREATE TABLE rail_lane (
    game_id   BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    owner     INT NOT NULL,
    from_x    INT NOT NULL,
    from_y    INT NOT NULL,
    to_x      INT NOT NULL,
    to_y      INT NOT NULL,
    PRIMARY KEY (game_id, owner, from_x, from_y, to_x, to_y)
);

CREATE TABLE rail_lane_cargo (
    game_id   BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    owner     INT NOT NULL,
    from_x    INT NOT NULL,
    from_y    INT NOT NULL,
    to_x      INT NOT NULL,
    to_y      INT NOT NULL,
    commodity TEXT NOT NULL,
    ordinal   INT NOT NULL,
    PRIMARY KEY (game_id, owner, from_x, from_y, to_x, to_y, commodity)
);
