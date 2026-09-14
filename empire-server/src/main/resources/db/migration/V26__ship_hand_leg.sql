-- Issues #201, #205: a sail pauses a standing order instead of ending it; she is on a hand leg until she arrives.
ALTER TABLE ship ADD COLUMN hand_leg BOOLEAN NOT NULL DEFAULT FALSE;
