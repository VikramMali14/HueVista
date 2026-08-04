-- A shop belongs to exactly one distributor.
--
-- Every read path already assumed this — resolveManageableShop, the network tree,
-- the shop-request record all do findByRetailerId(...).findFirst() — but nothing
-- enforced it. The entity declares a unique constraint on (distributor, retailer),
-- which only ever affected Hibernate's own DDL generation; production runs
-- ddl-auto=validate against this schema, where no such constraint was ever created.
-- So a shop could sit under two distributors, and which one "owned" it depended on
-- row order: both would see its data, both could restrict its catalogue, and it
-- would appear twice in the admin's network report.

-- Keep the earliest link for each shop and drop the rest. The oldest is the one the
-- shop was originally set up under, so this keeps the relationship that the shop's
-- existing brand and page grants were issued by.
DELETE FROM distributor_retailer_links a
      USING distributor_retailer_links b
      WHERE a.retailer_id = b.retailer_id
        AND a.id > b.id;

-- Now it can be a rule rather than a convention. This subsumes the intended
-- (distributor_id, retailer_id) pair uniqueness: one row per shop, full stop.
CREATE UNIQUE INDEX ux_distributor_retailer_links_retailer
    ON distributor_retailer_links (retailer_id);

-- Shops that predate the house distributor are linked to it on the next startup —
-- see ShopNetworkBackfill. That runs in Java rather than here because the house
-- organization row has to be built from the Organization entity's own defaults, and
-- a hand-written INSERT would silently drift from it the next time a NOT NULL column
-- is added (three have been added since the table was created).
