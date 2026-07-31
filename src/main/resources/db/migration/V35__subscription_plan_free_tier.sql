-- The free tier became the trial (Plan.FREE), but the subscriptions.plan check
-- constraint still only allowed the four PAID tiers. Every new shop account
-- therefore failed at signup, on the trial insert, with:
--   ERROR: new row for relation "subscriptions" violates check constraint "subscriptions_plan_check"
-- and the whole createRetailer transaction rolled back — no shop, no user, a 500
-- on the admin's "create shop" button. Widen the constraint to cover the tier the
-- trial is actually granted on.
--
-- Same shape as V21, which widened users_provider_check for ACCESS_CODE: the
-- constraint is generated from the enum when Hibernate first creates the table and
-- is never altered afterwards, so a value added to the enum needs a migration to
-- reach an existing database.

ALTER TABLE subscriptions
    DROP CONSTRAINT IF EXISTS subscriptions_plan_check;

ALTER TABLE subscriptions
    ADD CONSTRAINT subscriptions_plan_check CHECK (
        (plan)::text = ANY ((ARRAY[
            'FREE'::character varying,
            'STARTER'::character varying,
            'PROFESSIONAL'::character varying,
            'BUSINESS'::character varying,
            'ENTERPRISE'::character varying
        ])::text[])
    );
