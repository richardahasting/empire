-- Issue #106: world.terrain.continent_count was bound in WorldCfg but never read by
-- WorldGenerator. Removing the field from the record makes the config loader, which runs with
-- FAIL_ON_UNKNOWN_PROPERTIES, reject any stored snapshot that still carries the key — and a game
-- whose snapshot will not bind is dropped on the floor by GameService.loadAll with only a log line.
-- So the snapshots have to lose the key at the same time the record does.
UPDATE game
   SET config_yaml = regexp_replace(config_yaml, '(?n)^[ \t]*continent_count:[^\n]*\n', '', 'g')
 WHERE config_yaml LIKE '%continent_count%';
