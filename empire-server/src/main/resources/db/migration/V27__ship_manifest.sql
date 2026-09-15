-- The running manifest (issue #244): what each ship has caught, mined, cruised and delivered since she was built.
-- A row per ship and key ("caught food", "delivered lcm", ...), rewritten with the fleet. Ships from before this
-- migration start at nothing.
CREATE TABLE ship_manifest (
    game_id  BIGINT NOT NULL,
    ship_id  BIGINT NOT NULL,
    key      TEXT NOT NULL,
    qty      DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (game_id, ship_id, key),
    FOREIGN KEY (game_id, ship_id) REFERENCES ship(game_id, id) ON DELETE CASCADE
);
