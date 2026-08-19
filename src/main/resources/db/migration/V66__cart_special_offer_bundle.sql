-- The special-offer line on the customer's counter: three rooms and three pictures for the
-- price of two of each.
--
-- Why the receipt needs its own columns rather than being folded into the project and
-- credit lines it is made of. A cart_purchases row is what a refund or a dispute is argued
-- from, and the question it has to answer a year later is "what did they buy, and at what
-- price" — not "what did those quantities come to". Three projects rung up at ₹149 each and
-- one bundle rung up at ₹438 grant the same thing and are not the same purchase: only one
-- of them was sold as an offer, and only one of them can be honestly quoted back.
--
-- The composition of a bundle is configuration (app.customer-catalogue.bundle-*) and will
-- move. projects_granted and credits_granted already hold what THIS order actually handed
-- over, so a later change to the offer cannot rewrite an old one.
--
-- Zero on every existing row is a statement of fact, not a guess: the line did not exist
-- when they were written, so none of them bought one. The default carries that same
-- reading forward for any client that names no bundles.

ALTER TABLE cart_purchases
    ADD COLUMN IF NOT EXISTS bundle_qty integer NOT NULL DEFAULT 0,
    ADD COLUMN IF NOT EXISTS bundle_price_paise integer NOT NULL DEFAULT 0;
