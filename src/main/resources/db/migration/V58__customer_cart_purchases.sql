-- One payment, several things in it: the customer's basket.
--
-- Everything the product sold a customer before this was a single item behind a single
-- button — one project, or one AI credit, each with its own payment sheet. That is the
-- wrong shape for the person it is aimed at. Somebody doing up two rooms wants two projects
-- and four pictures, and the old flow made them open Checkout six times while paying no
-- attention to the fact that they had. A basket makes the size of the order visible to both
-- sides, which is the whole reason an offer at ₹289 can exist at all.
--
-- This table is that payment's receipt, and it is deliberately NOT two rows in
-- project_purchases and ai_credit_purchases. One payment bought both things, and the
-- discount applies to the basket rather than to any line in it — split across two ledgers
-- there is nowhere to attribute it, and any answer to "what did this customer actually pay
-- for that project" becomes an apportionment somebody has to invent later.

CREATE TABLE cart_purchases (
    id                  character varying(255) NOT NULL,

    -- The replay guard. A verified Razorpay signature stays valid for ever, so without a
    -- unique claim here a client could re-POST the same (order, payment, signature) triple
    -- and mint projects and credits out of one payment. The pre-check in the service keeps
    -- the common case readable; THIS is what makes it safe.
    payment_id          character varying(255) NOT NULL,
    order_id            character varying(255) NOT NULL,
    user_id             character varying(255) NOT NULL,

    -- The lines, as quantities beside the rate each was rung up at. Catalogue prices and
    -- offers are configuration and will move, so a stored total alone could not afterwards
    -- say whether "₹537" was three projects at ₹149 less 10% or something else at a later
    -- price. A dispute is argued from these six columns.
    project_qty         integer NOT NULL,
    project_price_paise integer NOT NULL,
    credit_qty          integer NOT NULL,
    credit_price_paise  integer NOT NULL,
    combo_qty           integer NOT NULL,
    combo_price_paise   integer NOT NULL,

    subtotal_paise      integer NOT NULL,
    discount_code       character varying(32),
    discount_percent    integer NOT NULL DEFAULT 0,
    discount_paise      integer NOT NULL DEFAULT 0,
    amount_paise        integer NOT NULL,

    -- What was handed over, with the combos already unpacked. Stored rather than recomputed
    -- from the quantities because the combo's composition is configuration too: changing it
    -- from "1 project + 2 credits" to something else must not rewrite what an order from
    -- last March turns out to have granted.
    projects_granted    integer NOT NULL,
    credits_granted     integer NOT NULL,

    -- The year both were sold with, captured here for the same reason project_credits
    -- captures its own: a later change to the standard window must not retroactively
    -- shorten something somebody already bought.
    valid_days          integer NOT NULL,

    created_at          timestamp(6) without time zone,

    CONSTRAINT cart_purchases_quantities_check
        CHECK (project_qty >= 0 AND credit_qty >= 0 AND combo_qty >= 0),
    CONSTRAINT cart_purchases_amount_check
        CHECK (amount_paise > 0 AND subtotal_paise > 0 AND discount_paise >= 0),
    CONSTRAINT cart_purchases_granted_check
        CHECK (projects_granted >= 0 AND credits_granted >= 0
               AND projects_granted + credits_granted > 0)
);

ALTER TABLE ONLY cart_purchases
    ADD CONSTRAINT cart_purchases_pkey PRIMARY KEY (id);

ALTER TABLE ONLY cart_purchases
    ADD CONSTRAINT uk_cart_purchase_payment UNIQUE (payment_id);

-- The only read: one customer's orders, newest first.
CREATE INDEX idx_cart_purchases_user ON cart_purchases (user_id, created_at DESC);
