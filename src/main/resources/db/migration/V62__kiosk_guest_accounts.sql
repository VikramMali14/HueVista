-- The kiosk walk-in gets an account, and a way back into it that isn't the receipt.
--
-- Three things change:
--   1. A kiosk code records the address the buyer gave at the till. That address — not
--      the printed code — is how they get back in. A redeemed code never expires, so
--      treating it as the credential would make a till slip a permanent password to
--      somebody's account.
--   2. A one-time code table for those emailed sign-ins. Separate from
--      verification_codes on purpose: those confirm an address for someone already
--      signed in, while these GRANT a session to someone signed in to nothing, and the
--      two must not share a namespace.
--   3. Users remember which account they were merged into, so support can answer
--      "where did that walk-in's room go" after the guest account is retired.

-- ── 1. Who bought the kiosk code ──────────────────────────────────────────────
ALTER TABLE customer_access_codes ADD COLUMN IF NOT EXISTS buyer_email character varying(320);

CREATE INDEX IF NOT EXISTS idx_access_codes_buyer_email
    ON customer_access_codes (buyer_email)
 WHERE buyer_email IS NOT NULL;

-- ── 2. Emailed sign-in codes ──────────────────────────────────────────────────
CREATE TABLE IF NOT EXISTS kiosk_reentry_codes (
    id            character varying(255) NOT NULL,
    user_id       character varying(255) NOT NULL,
    code_hash     character varying(255) NOT NULL,
    destination   character varying(320) NOT NULL,
    expires_at    timestamp(6) without time zone NOT NULL,
    attempts      integer NOT NULL DEFAULT 0,
    consumed      boolean NOT NULL DEFAULT false,
    created_at    timestamp(6) without time zone
);

ALTER TABLE ONLY kiosk_reentry_codes
    ADD CONSTRAINT kiosk_reentry_codes_pkey PRIMARY KEY (id);

-- Confirming a code looks the row up by the address it was sent to; the cooldown reads
-- the newest row for that address. Both are this index.
CREATE INDEX IF NOT EXISTS idx_kiosk_reentry_destination
    ON kiosk_reentry_codes (destination);
CREATE INDEX IF NOT EXISTS idx_kiosk_reentry_user
    ON kiosk_reentry_codes (user_id);

-- ── 3. Where a retired guest account's contents went ──────────────────────────
-- No foreign key: the target account may itself be deleted later, and losing the
-- forwarding note is worse than holding an id that no longer resolves.
ALTER TABLE users ADD COLUMN IF NOT EXISTS merged_into_user_id character varying(255);
