-- Let a grant exist without a distributor behind it.
--
-- Brand and page assignments both recorded the distributor that granted them, NOT NULL,
-- which quietly made an admin-created shop permanently unrestrictable: createRetailer
-- skips both grants when there is no distributor to attribute them to, and assignBrands
-- / assignFeatures then refuse outright with "this shop is not linked to a distributor".
-- So a shop an admin set up directly — the ordinary case for the platform's own
-- customers, and for any shop onboarded before its distributor existed — could only ever
-- have the run of the entire product.
--
-- An admin is above every distributor in the hierarchy and already resolves any shop in
-- resolveManageableShop; the column simply had nowhere to put "granted by the platform".
-- Null now means exactly that.
ALTER TABLE retailer_brand_assignments
    ALTER COLUMN distributor_id DROP NOT NULL;

ALTER TABLE retailer_feature_assignments
    ALTER COLUMN distributor_id DROP NOT NULL;
