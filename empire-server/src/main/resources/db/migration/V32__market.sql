-- The commodity market (issue #141; KNOWN Wolfpack Empire struct comstr). One row per open lot; ids are unique for
-- the life of a game. The goods in a lot are out of their sector and in nobody else's yet.
ALTER TABLE game ADD COLUMN next_lot_id BIGINT NOT NULL DEFAULT 1;
CREATE TABLE market_lot (
    game_id   BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    id        BIGINT NOT NULL,
    owner     INT NOT NULL,
    commodity TEXT NOT NULL,
    amount    DOUBLE PRECISION NOT NULL,
    price     DOUBLE PRECISION NOT NULL,
    bidder    INT NOT NULL DEFAULT -1,
    from_x    INT NOT NULL,
    from_y    INT NOT NULL,
    dest_x    INT,
    dest_y    INT,
    listed    BIGINT NOT NULL,
    settles   BIGINT NOT NULL,
    PRIMARY KEY (game_id, id)
);
