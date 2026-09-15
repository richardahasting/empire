-- Unrest (issue #72), the original's sct_loyal, sct_work, sct_oldown, sct_che and sct_che_target. Every existing
-- sector starts loyal, fully at work, its people its owner's (-1), with no guerrillas.
ALTER TABLE sector ADD COLUMN loyalty    SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sector ADD COLUMN work       SMALLINT NOT NULL DEFAULT 100;
ALTER TABLE sector ADD COLUMN old_owner  INT NOT NULL DEFAULT -1;
ALTER TABLE sector ADD COLUMN che        SMALLINT NOT NULL DEFAULT 0;
ALTER TABLE sector ADD COLUMN che_target INT NOT NULL DEFAULT -1;
