-- Retailer-assigned onboarding on customer access codes. At generation the
-- retailer now names the customer, assigns a quota of projects (charged against
-- their monthly image quota), and optionally unlocks individual shop products
-- (in addition to the existing whole-company allowedBrands). Redeeming a code
-- auto-provisions a passwordless CUSTOMER account and signs the customer in.

ALTER TABLE customer_access_codes
    ADD COLUMN customer_name character varying(120),
    ADD COLUMN project_quota integer NOT NULL DEFAULT 1,
    ADD COLUMN allowed_product_ids character varying(4096);
