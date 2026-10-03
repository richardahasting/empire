-- A satellite in orbit (issue #71; KNOWN PLN_LAUNCHED, PLN_SYNCHRONOUS, pln_theta): 'orbit' or 'geosync', null on the
-- ground; how far round its orbit it is; and the update it went up, for when it is first ready.
ALTER TABLE plane ADD COLUMN orbit VARCHAR(8);
ALTER TABLE plane ADD COLUMN theta DOUBLE PRECISION NOT NULL DEFAULT 0;
ALTER TABLE plane ADD COLUMN launched BIGINT NOT NULL DEFAULT 0;
