-- Ship missions (issue #56): "fish" wanders the grounds near a home harbour, lands the catch, repeats.
ALTER TABLE ship ADD COLUMN mission TEXT;
ALTER TABLE ship ADD COLUMN home_x INT;
ALTER TABLE ship ADD COLUMN home_y INT;
