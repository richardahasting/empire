-- Issue #65: a hull carries fuel. Not cargo — a hold full of petrol is freight, a full tank is not —
-- so it gets its own column rather than a row in ship_stock.
ALTER TABLE ship ADD COLUMN fuel DOUBLE PRECISION NOT NULL DEFAULT 0;
