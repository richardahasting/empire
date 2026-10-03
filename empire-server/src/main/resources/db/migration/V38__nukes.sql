-- Nuclear warheads (issue #71; KNOWN struct nukstr). Ids are unique for the life of a game. plane_id: the plane it is
-- armed on (0: stored at x,y); armed, it is wherever that plane is and whoever's that plane is.
ALTER TABLE game ADD COLUMN next_nuke_id BIGINT NOT NULL DEFAULT 1;
CREATE TABLE nuke (
    game_id  BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    id       BIGINT NOT NULL,
    owner    INT NOT NULL,
    class    TEXT NOT NULL,
    x        INT NOT NULL,
    y        INT NOT NULL,
    tech     DOUBLE PRECISION NOT NULL,
    built    BIGINT NOT NULL,
    plane_id BIGINT NOT NULL DEFAULT 0,
    airburst BOOLEAN NOT NULL DEFAULT FALSE,
    PRIMARY KEY (game_id, id)
);
