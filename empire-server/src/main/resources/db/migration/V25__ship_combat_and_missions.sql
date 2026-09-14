-- Issue #68, sea combat and missions.
-- fired_on: a ship that fired on a country at peace is fair game to it until an update. "1:130,4:131" (country:until).
-- route: a patrol's waypoints, or the station a blockade or interdiction holds. "3,4;7,9".
-- ward: the ship an escort stays with (0 = none).
ALTER TABLE ship ADD COLUMN fired_on TEXT NOT NULL DEFAULT '';
ALTER TABLE ship ADD COLUMN route TEXT NOT NULL DEFAULT '';
ALTER TABLE ship ADD COLUMN ward BIGINT NOT NULL DEFAULT 0;
