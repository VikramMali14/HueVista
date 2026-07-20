-- Prepaid billing wallet: the retailer adds money via Razorpay top-up and
-- spends it on pay-per-use overage (extra images at Rs. 50 + GST, extra AI
-- auto-masks at Rs. 25 + GST) once the plan's monthly allowances are spent.
-- Separate from the kiosk-earnings wallet (store package): top-ups are
-- prepaid usage money and are NOT redeemable as payouts.

CREATE TABLE billing_wallets (
    id character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL,
    balance_paise bigint NOT NULL DEFAULT 0,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,
    CONSTRAINT billing_wallets_pkey PRIMARY KEY (id),
    CONSTRAINT uk_billing_wallets_user UNIQUE (user_id)
);

CREATE TABLE billing_wallet_transactions (
    id character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL,
    amount_paise bigint NOT NULL,
    type character varying(32) NOT NULL,
    reference character varying(255),
    created_at timestamp(6) without time zone,
    CONSTRAINT billing_wallet_transactions_pkey PRIMARY KEY (id)
);

CREATE INDEX idx_billing_wallet_txn_user
    ON billing_wallet_transactions (user_id, created_at);

-- Pay-per-use AI auto-mask credits bought from the wallet; extends the
-- monthly auto-mask allowance and survives renewals, mirroring
-- purchased_image_credits from V16.
ALTER TABLE subscriptions
    ADD COLUMN purchased_auto_mask_credits integer NOT NULL DEFAULT 0;
