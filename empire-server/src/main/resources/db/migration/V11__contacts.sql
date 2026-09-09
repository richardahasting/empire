-- Detection contacts (issue #75): one country's sighting of one enemy ship, remembered where it was
-- last seen and aged out after detection.contact_staleness_updates.
CREATE TABLE contact (
    game_id      BIGINT NOT NULL REFERENCES game(id) ON DELETE CASCADE,
    owner        INT NOT NULL,
    ship_id      BIGINT NOT NULL,
    target_owner INT NOT NULL,
    class        TEXT NOT NULL,
    x            INT NOT NULL,
    y            INT NOT NULL,
    seen_update  BIGINT NOT NULL,
    confidence   DOUBLE PRECISION NOT NULL,
    PRIMARY KEY (game_id, owner, ship_id)
);
