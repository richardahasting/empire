-- Planes (issue #262, #71 slice 3a). Ids are unique for the life of a game.
ALTER TABLE game ADD COLUMN next_plane_id BIGINT NOT NULL DEFAULT 1;
CREATE TABLE plane (
    game_id    BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    id         BIGINT NOT NULL,
    owner      INT NOT NULL,
    class      TEXT NOT NULL,
    x          INT NOT NULL,
    y          INT NOT NULL,
    efficiency DOUBLE PRECISION NOT NULL,
    tech       DOUBLE PRECISION NOT NULL,
    built      BIGINT NOT NULL,
    note       TEXT NOT NULL DEFAULT '',
    PRIMARY KEY (game_id, id)
);
