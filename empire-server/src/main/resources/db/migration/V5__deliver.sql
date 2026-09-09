-- Standing delivery orders (issue #45): per sector and commodity, push everything above a
-- threshold one hex in a direction. NULL direction = no order.
ALTER TABLE sector_stock ADD COLUMN deliver_dir INT;
ALTER TABLE sector_stock ADD COLUMN deliver_threshold DOUBLE PRECISION;
