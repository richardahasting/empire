-- Ships (issue #56). Ids are unique for the life of a game: game.next_ship_id never goes backwards.
ALTER TABLE game ADD COLUMN next_ship_id BIGINT NOT NULL DEFAULT 1;
CREATE TABLE ship (
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    id            BIGINT NOT NULL,
    owner         INT NOT NULL,
    class         TEXT NOT NULL,
    name          TEXT NOT NULL DEFAULT '',
    x             INT NOT NULL,
    y             INT NOT NULL,
    efficiency    DOUBLE PRECISION NOT NULL,
    dest_x        INT,
    dest_y        INT,
    lane_from_x   INT,
    lane_from_y   INT,
    lane_to_x     INT,
    lane_to_y     INT,
    lane_cargo    TEXT,
    lane_outbound BOOLEAN NOT NULL DEFAULT FALSE,
    built         BIGINT NOT NULL DEFAULT 0,
    note          TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (game_id, id)
);
CREATE TABLE ship_stock (
    game_id   BIGINT NOT NULL,
    ship_id   BIGINT NOT NULL,
    commodity TEXT NOT NULL,
    qty       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (game_id, ship_id, commodity),
    FOREIGN KEY (game_id, ship_id) REFERENCES ship(game_id, id) ON DELETE CASCADE
);
