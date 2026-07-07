-- In-store kiosk ("order at the counter") links, the customer payments made
-- through them, and the retailer wallet redemption queue.
--
-- A retailer publishes a public store link with a price per image (>= the
-- platform base of Rs.50). A walk-in customer pays that price at /store/<slug>,
-- which buys them one access code (auto-redeemed into a guest studio session).
-- The platform keeps the base; the excess accrues to the retailer's wallet.
-- Balances are derived: SUM(store_payments.retailer_share_paise) minus
-- SUM(wallet_redemptions.amount_paise WHERE status <> 'REJECTED') — a PENDING
-- redemption holds the funds, a REJECTED one returns them. Payouts are manual:
-- the retailer requests with their UPI id, the admin approves and pays.

CREATE TABLE store_links (
    id character varying(255) NOT NULL,
    organization_id character varying(255) NOT NULL,
    slug character varying(80) NOT NULL,
    price_paise integer NOT NULL,
    valid_days integer NOT NULL,
    active boolean DEFAULT true NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone
);

ALTER TABLE ONLY store_links
    ADD CONSTRAINT store_links_pkey PRIMARY KEY (id);
ALTER TABLE ONLY store_links
    ADD CONSTRAINT uk_store_links_slug UNIQUE (slug);
ALTER TABLE ONLY store_links
    ADD CONSTRAINT fk_store_links_org FOREIGN KEY (organization_id) REFERENCES organizations(id);
CREATE INDEX idx_store_links_org ON store_links (organization_id);

CREATE TABLE store_payments (
    id character varying(255) NOT NULL,
    store_link_id character varying(255) NOT NULL,
    organization_id character varying(255) NOT NULL,
    payment_id character varying(255) NOT NULL,
    order_id character varying(255) NOT NULL,
    amount_paise integer NOT NULL,
    platform_fee_paise integer NOT NULL,
    retailer_share_paise integer NOT NULL,
    access_code_id character varying(255),
    created_at timestamp(6) without time zone
);

ALTER TABLE ONLY store_payments
    ADD CONSTRAINT store_payments_pkey PRIMARY KEY (id);
-- One verified Razorpay payment buys exactly one code (replay backstop).
ALTER TABLE ONLY store_payments
    ADD CONSTRAINT uk_store_payments_payment_id UNIQUE (payment_id);
ALTER TABLE ONLY store_payments
    ADD CONSTRAINT fk_store_payments_link FOREIGN KEY (store_link_id) REFERENCES store_links(id);
ALTER TABLE ONLY store_payments
    ADD CONSTRAINT fk_store_payments_org FOREIGN KEY (organization_id) REFERENCES organizations(id);
ALTER TABLE ONLY store_payments
    ADD CONSTRAINT fk_store_payments_code FOREIGN KEY (access_code_id) REFERENCES customer_access_codes(id);
CREATE INDEX idx_store_payments_org_created ON store_payments (organization_id, created_at DESC);

CREATE TABLE wallet_redemptions (
    id character varying(255) NOT NULL,
    organization_id character varying(255) NOT NULL,
    amount_paise integer NOT NULL,
    upi_id character varying(255) NOT NULL,
    status character varying(20) NOT NULL,
    requested_by_user_id character varying(255) NOT NULL,
    decided_by_user_id character varying(255),
    decided_at timestamp(6) without time zone,
    admin_note character varying(1000),
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone
);

ALTER TABLE ONLY wallet_redemptions
    ADD CONSTRAINT wallet_redemptions_pkey PRIMARY KEY (id);
ALTER TABLE ONLY wallet_redemptions
    ADD CONSTRAINT fk_wallet_redemptions_org FOREIGN KEY (organization_id) REFERENCES organizations(id);
CREATE INDEX idx_wallet_redemptions_org_created ON wallet_redemptions (organization_id, created_at DESC);
CREATE INDEX idx_wallet_redemptions_status ON wallet_redemptions (status);
