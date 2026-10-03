-- A plane's standing mission (issue #71; KNOWN miss.c): air defence around an op point, within a radius.
ALTER TABLE plane ADD COLUMN mission TEXT;
ALTER TABLE plane ADD COLUMN op_x INT;
ALTER TABLE plane ADD COLUMN op_y INT;
ALTER TABLE plane ADD COLUMN radius INT NOT NULL DEFAULT 0;
