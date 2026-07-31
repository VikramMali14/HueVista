-- The two quotas become one: a PROJECT.
--
-- A shop used to budget two separate allowances — images (the compulsory AI photo
-- clean-up, charged on every image) and auto-masks (AI wall detection, charged only
-- when chosen). They ran out independently, so the common failure was a shop with
-- clean-up left and no auto-mask: a cleaned photo it then had to mask by hand, having
-- paid for a plan whose headline feature it could no longer reach. Nothing about that
-- split was visible when buying, either — two numbers on a pricing card that a counter
-- owner had to model against their own month to compare tiers at all.
--
-- One project now covers the whole automatic pipeline, clean-up and wall detection
-- together, and is charged once. Plans are sized in projects (Starter 15,
-- Professional 45, Business 100) and extra projects are bought at the tier's own rate,
-- cheaper the bigger the plan.

-- ---------------------------------------------------------------------------
-- 1. Subscriptions: rename the image quota to what it now counts
-- ---------------------------------------------------------------------------
--
-- Renamed rather than replaced so every live row keeps its usage, its holds and its
-- paid-for extras. The columns already counted whole pipeline runs in practice — the
-- clean-up was compulsory, so an image and a project were one and the same thing on
-- every row that exists.

ALTER TABLE subscriptions RENAME COLUMN ai_generations_used     TO projects_used;
ALTER TABLE subscriptions RENAME COLUMN ai_generations_limit    TO projects_limit;
ALTER TABLE subscriptions RENAME COLUMN reserved_images         TO reserved_projects;
ALTER TABLE subscriptions RENAME COLUMN purchased_image_credits TO purchased_project_credits;

-- The holds a code carries are counted in two places and must stay in lock-step with
-- the subscription's, so the code side is renamed to match rather than left describing
-- a unit that no longer exists.
ALTER TABLE customer_access_codes RENAME COLUMN reserved_images TO reserved_projects;

-- ---------------------------------------------------------------------------
-- 2. Fold the auto-mask allowance away
-- ---------------------------------------------------------------------------
--
-- Auto-masking is included in a project now, so a separate allowance has nothing left
-- to meter. Purchased auto-mask credits, though, are money the shop actually spent, and
-- dropping the column would destroy them. Each becomes a whole project credit: worth
-- more than the auto-mask it replaces (a project runs the clean-up too), which is the
-- right direction for a conversion nobody asked for. Volumes are tiny and the
-- alternative — refunding a fraction of a project — cannot be spent.

UPDATE subscriptions
   SET purchased_project_credits = purchased_project_credits
                                   + COALESCE(purchased_auto_mask_credits, 0)
 WHERE COALESCE(purchased_auto_mask_credits, 0) > 0;

ALTER TABLE subscriptions
    DROP COLUMN auto_masks_used,
    DROP COLUMN auto_masks_limit,
    DROP COLUMN purchased_auto_mask_credits;

-- ---------------------------------------------------------------------------
-- 3. Carried-over projects, and the quantity that sizes an allowance
-- ---------------------------------------------------------------------------
--
-- carried_project_credits holds what was left of the previous plan's monthly allowance
-- when a shop upgraded mid-cycle. Before this, upgrading forfeited it: a shop on
-- Starter with 5 projects left that moved to Professional started the new plan at
-- exactly 45, having paid for 50. The credits move across on upgrade instead.
--
-- They are part of a MONTHLY allowance, so unlike purchased_project_credits they do
-- expire — zeroed on renewal with projects_used. A shop has the cycle it upgraded into
-- to spend them, not forever.
--
-- quantity records how many of the plan Razorpay bills for. Renewal rebuilds the
-- allowance from the plan (that is how a change to a tier's quota reaches existing
-- customers) and without the multiplier it could only rebuild a single plan's worth,
-- cutting a shop paying 3x down to 1x on its next renewal.
ALTER TABLE subscriptions
    ADD COLUMN carried_project_credits integer NOT NULL DEFAULT 0,
    ADD COLUMN quantity                integer NOT NULL DEFAULT 1;

-- Recover the quantity each live row was actually sold, from the allowance it carries.
-- A subscription bought at 3x was created with 3x the plan's limit, so the ratio gives
-- the multiplier back. Rows that don't divide cleanly (an admin-granted custom
-- allowance) keep quantity 1, which is what they were billed for.
UPDATE subscriptions s
   SET quantity = GREATEST(1, s.projects_limit / p.old_limit)
  FROM (VALUES ('FREE', 3), ('STARTER', 20), ('PROFESSIONAL', 60), ('BUSINESS', 120))
       AS p(plan, old_limit)
 WHERE p.plan = s.plan
   AND s.projects_limit > 0
   AND s.projects_limit % p.old_limit = 0;

-- ---------------------------------------------------------------------------
-- 4. The nightly hold-sweep index follows its column
-- ---------------------------------------------------------------------------
DROP INDEX IF EXISTS idx_cac_unswept_holds;
CREATE INDEX idx_cac_unswept_holds
    ON customer_access_codes (expires_at)
 WHERE used_at IS NULL
   AND used_by_user_id IS NULL
   AND revoked_at IS NULL
   AND quota_released_at IS NULL
   AND reserved_projects > 0;

-- ---------------------------------------------------------------------------
-- 5. One-off project purchases paid with money
-- ---------------------------------------------------------------------------
--
-- Extra projects can be bought with points or with money, and the two prices differ by
-- tier (₹99 with no plan, down to ₹45 on Business). The cash rail needs the same
-- one-payment-one-credit guard points_purchases gives top-ups: the Checkout signature
-- is a plain HMAC with no nonce and no expiry, so without a claimed payment id a client
-- could re-POST one valid triple and mint a project credit on every replay.
CREATE TABLE project_purchases (
    id            varchar(255) PRIMARY KEY,
    payment_id    varchar(255) NOT NULL UNIQUE,
    order_id      varchar(255) NOT NULL,
    user_id       varchar(255) NOT NULL,
    plan          varchar(32),
    amount_paise  integer      NOT NULL,
    created_at    timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX idx_project_purchases_user ON project_purchases (user_id);

-- Existing plans keep the allowance they were sold for the rest of the cycle they have
-- already paid for; renewal rebuilds it from the plan's new project count. Leaving the
-- current cycle alone deliberately errs generous — a shop mid-month on the old Starter
-- keeps 20 runs instead of dropping to 15 overnight — and every one of them now
-- includes the auto-mask it previously had to budget separately.
