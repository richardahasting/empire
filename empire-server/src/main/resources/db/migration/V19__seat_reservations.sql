-- Issue #126: claiming a country is the sign-up. A visitor names a seat and gives an email before
-- they have an account, so the claim has to happen before anything is proven about them.
--
-- The seat is therefore RESERVED, not taken: account_id stays null until the magic link is clicked.
-- Until then the seat does not count as filled, which matters because the starting bell (#123) fires
-- when the last seat goes — without this, a stranger with invented addresses could start the game.
--
-- The chosen name waits in pending_name rather than being applied, so a reservation that lapses
-- leaves no trace and the seat goes back to being emp-whatever with nothing to undo.
ALTER TABLE country
    ADD COLUMN reserved_by     BIGINT REFERENCES account(id),
    ADD COLUMN reserved_until  TIMESTAMPTZ,
    ADD COLUMN pending_name    TEXT;

CREATE INDEX country_reserved ON country(game_id) WHERE reserved_by IS NOT NULL;
