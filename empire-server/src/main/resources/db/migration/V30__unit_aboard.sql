-- A land unit may be aboard a ship (issue #252). 0 = ashore.
ALTER TABLE land_unit ADD COLUMN ship_id BIGINT NOT NULL DEFAULT 0;
