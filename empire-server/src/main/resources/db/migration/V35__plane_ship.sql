-- The carrier a plane is aboard (issue #71; KNOWN pln_ship), 0 when it is ashore.
ALTER TABLE plane ADD COLUMN ship_id BIGINT NOT NULL DEFAULT 0;
