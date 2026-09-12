-- Issue #69: a sail happens immediately. A ship gets a mobility pool of its own, as a sector has:
-- it fills up each update by the ship's speed, a standing mission spends it at the update, and a
-- player's sail spends it there and then. A ship that has been sitting still can therefore dash,
-- and one that has been working all update cannot.
--
-- Richard, 2026-09-11: "A move happens immediately. I guess it's a sail actually. But I want them to
-- have the ability to have some autonomy as well" — so both: order it now, or let the mission run it.
ALTER TABLE ship ADD COLUMN mobility DOUBLE PRECISION NOT NULL DEFAULT 0;
