-- Ships, planes and land units up for sale (issue #141; KNOWN Wolfpack Empire struct trdstr). One row per lot; ids are
-- unique for the life of a game. The thing for sale stays in its own table; this only says it is on the block.
ALTER TABLE game ADD COLUMN next_trade_id BIGINT NOT NULL DEFAULT 1;
CREATE TABLE trade_lot (
    game_id BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    id      BIGINT NOT NULL,
    owner   INT NOT NULL,
    kind    TEXT NOT NULL,
    item    BIGINT NOT NULL,
    price   DOUBLE PRECISION NOT NULL,
    bidder  INT NOT NULL DEFAULT -1,
    dest_x  INT,
    dest_y  INT,
    listed  BIGINT NOT NULL,
    settles BIGINT NOT NULL,
    PRIMARY KEY (game_id, id)
);
