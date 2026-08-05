-- Remember which charge a subscription last applied, so one payment is applied once.
--
-- docs/RAZORPAY_SETUP.md has merchants tick both subscription.charged AND
-- payment.captured, and Razorpay sends both for the same renewal. They are DIFFERENT
-- events with different ids, so the processed_webhook_events guard passes them both
-- through — correctly, since that table exists to stop a REDELIVERY of one event, not
-- two events describing one charge.
--
-- The result was that every charge was applied twice: two billing cycles counted for one
-- payment, and the second pass — no longer the plan's first charge — expired the projects
-- an upgrade had just carried onto the new plan.
--
-- Nullable with no backfill on purpose. NULL means "no charge applied yet", which is the
-- correct reading for every existing row: the next charge on any of them is a genuine one
-- and must be applied. Guessing a value here would make the first real charge after this
-- migration look like a duplicate and be skipped.
ALTER TABLE subscriptions
    ADD COLUMN last_charge_payment_id VARCHAR(255);
