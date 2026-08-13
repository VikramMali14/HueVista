-- The AI image wallet, and the end of the free image on a room somebody else paid for.
--
-- Two changes that only make sense together.
--
-- FIRST, the thing that was wrong. Every project was created with renders_allowed = 1, so
-- every project carried one free photorealistic render. That is the right bargain when the
-- account creating the room is also the account paying for it — a shop working its own
-- walls has bought the room and the picture at the end of it is what the room is for. It is
-- the wrong bargain for a room a shop GIVES AWAY. There the shop spends one of its monthly
-- project credits so a customer can try colours and leave with a colour board; nobody has
-- paid for the model call, and the product handed one over anyway, once per access code
-- issued and once per project granted. The shop's quota was quietly funding a Nano Banana
-- Pro call it never agreed to.
--
-- SECOND, what a customer does instead. A CUSTOMER account cannot hold reward points (they
-- are shop-side, and everything they buy is shop-side) and cannot buy a subscription at all
-- — BillingService refuses one outright. So the customer who wants the picture had exactly
-- one route: the per-project cash top-up. That works, and it is a payment sheet per image
-- with nothing carried between projects. This migration adds the wallet beside it: credits
-- bought once and spent on any room, one credit for one image, and the same wallet for a
-- shop that would rather hold images in advance than pay per project.
--
-- The two rails are priced identically on purpose (₹198 list, 50% off at launch = ₹99,
-- against the ₹99 per-project top-up), so nobody is ever worse off for having topped up.
--
-- NOT retroactive. Rooms that already exist keep the allowance they were created with,
-- shop-funded ones included. That image was promised the day the room was made, and taking
-- it back to settle a pricing question would break a promise to a customer who can see the
-- button. The column default stays 1; the ZERO is chosen per project in application code
-- (ProjectService#includedRenders), which is the only place that knows who paid.

-- ---------------------------------------------------------------------------
-- 1. The wallet
-- ---------------------------------------------------------------------------
--
-- One row per account, and the balance lives ON it rather than being summed from the
-- journal on every read. A render spends a credit on the request thread, and two browser
-- tabs must not both pass a "you have one left" check against the same credit — so the
-- spend is a conditional UPDATE (balance >= :credits) and the database decides which tab
-- wins. A summed balance has no row to guard and no way to express that.
--
-- No expiry column, and that is deliberate rather than an omission. Reward points expire
-- because they are earned, dated and promotional. A credit is a prepayment for a specific
-- piece of work at a fixed price, so expiring one is charging for nothing.
--
-- No foreign key to users. A wallet is a financial record: deleting an account must not
-- silently delete the evidence of what it bought, and the balance means nothing to anyone
-- else. The unique constraint on user_id is the one that matters — it is what makes the
-- open-on-first-credit path safe when two purchases land at once.
CREATE TABLE ai_credit_wallets (
    id         character varying(255) NOT NULL,
    user_id    character varying(255) NOT NULL,
    balance    integer NOT NULL DEFAULT 0,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    CONSTRAINT ai_credit_wallets_balance_check CHECK (balance >= 0)
);

ALTER TABLE ONLY ai_credit_wallets
    ADD CONSTRAINT ai_credit_wallets_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ai_credit_wallets
    ADD CONSTRAINT uk_ai_credit_wallet_user UNIQUE (user_id);

-- ---------------------------------------------------------------------------
-- 2. The statement
-- ---------------------------------------------------------------------------
--
-- Append-only. The wallet holds the spending power; this says how it got there, which is
-- what a support request about a missing credit is actually asking.
--
-- balance_after is redundant with replaying the journal and worth the column anyway: the
-- holder reading "1 credit spent" wants to know what they had left, and one row answers it
-- instead of a running sum over every row that came before.
CREATE TABLE ai_credit_transactions (
    id            character varying(255) NOT NULL,
    user_id       character varying(255) NOT NULL,
    credits       integer NOT NULL,
    type          character varying(32) NOT NULL,
    balance_after integer NOT NULL DEFAULT 0,
    reference     character varying(255),
    note          character varying(255),
    created_at    timestamp(6) without time zone,
    CONSTRAINT ai_credit_transactions_type_check
        CHECK ((type)::text = ANY ((ARRAY['PURCHASED'::character varying,
                                          'SPENT_ON_RENDER'::character varying,
                                          'RENDER_REFUNDED'::character varying,
                                          'GRANTED'::character varying])::text[]))
);

ALTER TABLE ONLY ai_credit_transactions
    ADD CONSTRAINT ai_credit_transactions_pkey PRIMARY KEY (id);

-- The only read: one account's recent movement, newest first.
CREATE INDEX idx_ai_credit_txn_user ON ai_credit_transactions (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 3. Replay protection for the money
-- ---------------------------------------------------------------------------
--
-- The unique payment_id is the whole point of this table. A verified Checkout signature
-- stays valid on every replay, so without a claimed-payments row a client could re-POST the
-- same (order_id, payment_id, signature) triple and mint credits out of one payment. The
-- pre-check in the service keeps the common case readable; THIS is what makes it safe.
--
-- The price is broken out rather than stored as a total. The launch discount is a
-- configured number that will change, and a receipt that only says "₹99" cannot afterwards
-- say whether that was one credit at the launch rate or something else at a later one.
CREATE TABLE ai_credit_purchases (
    id               character varying(255) NOT NULL,
    payment_id       character varying(255) NOT NULL,
    order_id         character varying(255) NOT NULL,
    user_id          character varying(255) NOT NULL,
    credits          integer NOT NULL,
    list_price_paise integer NOT NULL,
    discount_percent integer NOT NULL DEFAULT 0,
    amount_paise     integer NOT NULL,
    created_at       timestamp(6) without time zone
);

ALTER TABLE ONLY ai_credit_purchases
    ADD CONSTRAINT ai_credit_purchases_pkey PRIMARY KEY (id);

ALTER TABLE ONLY ai_credit_purchases
    ADD CONSTRAINT uk_ai_credit_purchase_payment UNIQUE (payment_id);

CREATE INDEX idx_ai_credit_purchases_user ON ai_credit_purchases (user_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. Which pocket paid for a render
-- ---------------------------------------------------------------------------
--
-- A render can now be paid for two ways, and the refund on failure has to go back where the
-- charge came from. Reading that off the project instead would get it wrong in exactly the
-- case this migration creates: a shop-granted project has renders_allowed = 0 and
-- renders_used = 0, so the old "decrement renders_used" refund would find nothing to give
-- back and the customer's ₹99 credit would be quietly kept for a picture the model refused
-- to make.
--
-- paid_by_user_id is stored rather than re-derived from the project at refund time because
-- the refund runs on the worker thread minutes later, and a project re-pointed at a new
-- account in between — which happens the moment a guest signs up — would send the credit to
-- the wrong wallet.
--
-- credits_spent is stored for the same reason: a price change between the charge and the
-- failure must not alter what an already-charged render owes back.
--
-- FALSE/NULL/0 on every existing row is correct, not a guess: credits did not exist when
-- they were written, so all of them were paid out of the project allowance.
ALTER TABLE project_renders
    ADD COLUMN paid_with_credit boolean NOT NULL DEFAULT false,
    ADD COLUMN paid_by_user_id  character varying(64),
    ADD COLUMN credits_spent    integer NOT NULL DEFAULT 0;
