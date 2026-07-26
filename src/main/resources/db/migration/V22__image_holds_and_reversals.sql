-- Image-credit HOLDS, code revocation, payment reversal and explicit brand restriction.
--
-- Four independent fixes land together because they all add columns the entities now
-- validate against:
--
--  1. HOLDS. Assigning a customer access code used to CHARGE the shop's image quota
--     outright (ai_generations_used += project_quota) and then charge AGAIN when the
--     project was actually rendered — the shop paid twice — while a code nobody
--     redeemed kept the quota forever. Those images are now HELD (reserved_images),
--     moved into usage only when a project is really segmented, and returned to the
--     shop when the code is revoked or swept after expiring unredeemed.
--  2. REVOCATION. A code can now be cancelled before redemption (revoked_at) and its
--     hold refunded exactly once (quota_released_at as the compare-and-set marker).
--  3. REVERSAL. A refunded or charged-back kiosk payment stops counting toward the
--     retailer's redeemable wallet balance (reversed_at / refunded_paise).
--  4. BRAND RESTRICTION. "Which brands may this shop offer" was inferred from the
--     presence of assignment rows, so revoking a shop's LAST brand granted it the
--     whole catalogue. Now an explicit flag.

-- ---------------------------------------------------------------------------
-- 1. Columns
-- ---------------------------------------------------------------------------

ALTER TABLE customer_access_codes
    ADD COLUMN reserved_images   integer NOT NULL DEFAULT 0,
    ADD COLUMN revoked_at        timestamp(6) without time zone,
    ADD COLUMN quota_released_at timestamp(6) without time zone;

ALTER TABLE subscriptions
    ADD COLUMN reserved_images        integer NOT NULL DEFAULT 0,
    ADD COLUMN trial_projects_created integer NOT NULL DEFAULT 0;

ALTER TABLE store_payments
    ADD COLUMN reversed_at    timestamp(6) without time zone,
    ADD COLUMN refunded_paise integer NOT NULL DEFAULT 0;

ALTER TABLE organizations
    ADD COLUMN brands_restricted boolean NOT NULL DEFAULT false;

-- The nightly sweep looks for expired, unredeemed, unrefunded codes that still hold
-- credits. A partial index keeps that a lookup rather than a full scan as the code
-- table grows.
CREATE INDEX idx_cac_unswept_holds
    ON customer_access_codes (expires_at)
 WHERE used_at IS NULL
   AND used_by_user_id IS NULL
   AND revoked_at IS NULL
   AND quota_released_at IS NULL
   AND reserved_images > 0;

-- ---------------------------------------------------------------------------
-- 2. Backfill: convert legacy pre-charges into real holds
-- ---------------------------------------------------------------------------
--
-- A code that is still outstanding (never redeemed, not yet expired) was charged to
-- its shop's ai_generations_used at generation time under the old rules, and holds
-- nothing under the new ones. Left alone, its quota would be counted TWICE the moment
-- this deploys: once as that old usage, once as the new hold. So the charge is moved
-- across — usage down, holds up — which leaves every shop's remaining allowance
-- exactly as it is today while making revoke/expiry refunds work.
--
-- Deliberately limited to UNREDEEMED live codes:
--   * an already-redeemed code may have rendered some of its projects, and how many is
--     not reliably reconstructable — those fall through the documented "legacy code
--     holds nothing, charge normally" path instead, which is what happens today anyway;
--   * an expired code's window (10 days) has closed, so nothing is owed back;
--   * a kiosk code (issued against a store payment, customer_name null) was never
--     charged to the shop at all — the walk-in customer paid for it.

-- The subscription each shop's holds belong to: the OWNER of the issuing organization,
-- resolved exactly the way the application resolves it (org_memberships, role OWNER —
-- NOT organizations.owner_user_id, which the billing path never consults), then that
-- owner's entitling plan — a genuinely ACTIVE one first, otherwise one winding down but
-- still inside its paid period, newest first.
--
-- Shops that resolve to nothing are dropped from the backfill ENTIRELY, codes included.
-- Setting a code's hold without the matching subscription hold would break the invariant
-- the runtime depends on — every held credit is counted in exactly two places — and
-- leave a code able to "spend" a credit no subscription ever reserved.
CREATE TEMP TABLE billable_shop ON COMMIT DROP AS
SELECT o.id AS organization_id, picked.subscription_id
  FROM organizations o
  JOIN LATERAL (
        SELECT s.id AS subscription_id
          FROM org_memberships m
          JOIN subscriptions s ON s.user_id = m.user_id
         WHERE m.organization_id = o.id
           AND m.role = 'OWNER'
           AND (s.status = 'ACTIVE'
                OR (s.status = 'CANCELLED'
                    AND s.current_period_end IS NOT NULL
                    AND s.current_period_end > LOCALTIMESTAMP))
         ORDER BY CASE WHEN s.status = 'ACTIVE' THEN 0 ELSE 1 END, s.created_at DESC
         LIMIT 1
       ) picked ON true;

CREATE TEMP TABLE legacy_code_holds ON COMMIT DROP AS
SELECT c.id            AS code_id,
       b.subscription_id,
       c.project_quota AS held
  FROM customer_access_codes c
  JOIN billable_shop b ON b.organization_id = c.organization_id
 WHERE c.used_at IS NULL
   AND c.used_by_user_id IS NULL
   AND c.expires_at > LOCALTIMESTAMP
   AND c.customer_name IS NOT NULL
   AND c.project_quota > 0
   AND NOT EXISTS (SELECT 1 FROM store_payments p WHERE p.access_code_id = c.id);

UPDATE customer_access_codes c
   SET reserved_images = h.held
  FROM legacy_code_holds h
 WHERE h.code_id = c.id;

UPDATE subscriptions s
   SET reserved_images     = h.held,
       ai_generations_used = GREATEST(s.ai_generations_used - h.held, 0)
  FROM (SELECT subscription_id, SUM(held)::integer AS held
          FROM legacy_code_holds
         GROUP BY subscription_id) h
 WHERE h.subscription_id = s.id;

-- ---------------------------------------------------------------------------
-- 3. Backfill: trial project slots
-- ---------------------------------------------------------------------------
--
-- The trial's one-project allowance is now claimed against a monotonic counter instead
-- of a live row count (counting rows meant deleting the trial project handed the slot
-- straight back). Seed the counter from the live count so no trial account that has
-- already used its project silently gets a second one.

UPDATE subscriptions s
   SET trial_projects_created = c.projects
  FROM (SELECT user_id, COUNT(*)::integer AS projects
          FROM projects
         WHERE user_id IS NOT NULL
         GROUP BY user_id) c
 WHERE c.user_id = s.user_id
   AND s.trial = true;

-- ---------------------------------------------------------------------------
-- 4. Backfill: brand restriction flag
-- ---------------------------------------------------------------------------
--
-- Preserve today's behaviour exactly: a shop that carries assigned brands stays
-- restricted to them; a shop with no assignments has never been filtered and stays
-- unrestricted. From here on the flag is set explicitly when a distributor first
-- assigns a brand, so revoking the last one leaves the shop restricted to nothing
-- rather than opening the whole catalogue.

UPDATE organizations o
   SET brands_restricted = true
 WHERE EXISTS (SELECT 1 FROM retailer_brand_assignments a WHERE a.retailer_id = o.id);
