-- Let a shop delete a kiosk link, without losing what it sold.
--
-- The shop could pause a store link but never remove one, so a link printed for a
-- counter that has since closed stayed in the list for good. Deleting it for real
-- is not an option: store_payments.store_link_id is NOT NULL, so dropping the row
-- would take the shop's own sales history — and the points audit that explains its
-- balance — along with it.
--
-- So the link is retired instead. A retired link stops serving its slug the moment
-- it is deleted and leaves the shop's list, while its payments, its codes and the
-- walk-ins holding them are untouched: they paid for that access and keep it.
ALTER TABLE store_links ADD COLUMN IF NOT EXISTS deleted_at TIMESTAMP NULL;

-- Every lookup that serves the public kiosk or lists a shop's links now filters on
-- this, and both are hot paths on a slug or an org.
CREATE INDEX IF NOT EXISTS idx_store_links_org_deleted
    ON store_links (organization_id, deleted_at);
