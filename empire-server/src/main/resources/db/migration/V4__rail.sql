ALTER TABLE sector ADD COLUMN rail_target DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE held_parcel ADD COLUMN mode TEXT NOT NULL DEFAULT 'road';
CREATE TABLE rail_order (
    id            BIGSERIAL PRIMARY KEY,
    game_id       BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    owner         INT NOT NULL,
    from_x        INT NOT NULL,
    from_y        INT NOT NULL,
    to_x          INT NOT NULL,
    to_y          INT NOT NULL,
    commodity     TEXT NOT NULL,
    qty           DOUBLE PRECISION NOT NULL,
    issued_update BIGINT NOT NULL
);
