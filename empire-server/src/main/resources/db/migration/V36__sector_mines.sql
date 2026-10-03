-- Mines (issue #71; KNOWN sct_mines): sea mines on the sea, land mines on land, a count per sector.
ALTER TABLE sector ADD COLUMN mines INT NOT NULL DEFAULT 0;
