-- Land units (issue #247, #71 slice 2). Ids are unique for the life of a game.
ALTER TABLE game ADD COLUMN next_unit_id BIGINT NOT NULL DEFAULT 1;
CREATE TABLE land_unit (
    game_id    BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    id         BIGINT NOT NULL,
    owner      INT NOT NULL,
    class      TEXT NOT NULL,
    x          INT NOT NULL,
    y          INT NOT NULL,
    efficiency DOUBLE PRECISION NOT NULL,
    mobility   DOUBLE PRECISION NOT NULL,
    tech       DOUBLE PRECISION NOT NULL,
    built      BIGINT NOT NULL,
    note       TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (game_id, id)
);
CREATE TABLE land_unit_stock (
    game_id   BIGINT NOT NULL,
    unit_id   BIGINT NOT NULL,
    commodity TEXT NOT NULL,
    qty       DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (game_id, unit_id, commodity),
    FOREIGN KEY (game_id, unit_id) REFERENCES land_unit(game_id, id) ON DELETE CASCADE
);
