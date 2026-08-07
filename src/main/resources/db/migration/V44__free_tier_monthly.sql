-- The free tier stops being a seven-day trial and becomes a standing monthly plan:
-- two projects a month, renewing for as long as the account exists, with colour
-- matching (the Colour finder) reserved for the paid tiers.
--
-- Three things have to change in the data for that to be true of the shops already
-- here, not just of the ones who sign up next.

-- 1. A free-tier row is no longer a trial.
--
-- `trial` means "granted for a fixed window and then gone", and the free tier is
-- neither. The flag is read in a dozen places — what blocks buying a plan, what the
-- nightly sweep expires, which rate extras are priced at — and leaving it set would
-- have every one of them treat a standing plan as a countdown. The plan column already
-- says FREE, which is the fact those call sites actually want.
UPDATE subscriptions
   SET trial = false
 WHERE plan = 'FREE';

-- 2. Live free rows get a month, from now.
--
-- These are the shops mid-trial when this ships. Their period ends within the week and
-- nothing renews it, so without this they would expire into the dead end the free tier
-- was built to remove. The usage counters are zeroed with the period because this IS a
-- renewal — the first of many — and starting a shop's new month on last week's spent
-- counter would hand it a month it had already used.
--
-- trial_projects_created goes back to zero for the same reason: it was the monotonic
-- gate behind the seven-day trial and nothing resets it, so a row carrying 2 would have
-- been capped forever by a counter that no longer governs anything.
--
-- purchased_project_credits and reserved_projects are deliberately untouched. The first
-- is money the shop spent and never expires; the second is held behind access codes its
-- customers have not redeemed yet, and clearing it would charge the shop twice for those
-- projects when they finally are.
UPDATE subscriptions
   SET current_period_start   = now(),
       current_period_end     = now() + interval '1 month',
       projects_used          = 0,
       pdf_downloads_used     = 0,
       carried_project_credits = 0,
       trial_projects_created = 0,
       cancel_at_period_end   = false,
       projects_limit         = 2,
       pdf_downloads_limit    = 5,
       pdf_image_limit        = 4
 WHERE plan = 'FREE'
   AND (status = 'ACTIVE'
        OR (status = 'CANCELLED' AND current_period_end IS NOT NULL
            AND current_period_end > now()));

-- 3. Shops whose trial already lapsed come back onto the free tier.
--
-- The point of the tier is that no shop is ever locked out of the product while it
-- decides. That has to include the shops the old seven-day window already shut out —
-- otherwise "we have a free plan" is true only for accounts created after this
-- migration, and the shops with the longest history with us are the ones it excludes.
--
-- Exactly one row per user is revived, the newest, and only where NOTHING currently
-- entitles them. The entitlement lookup prefers the newest ACTIVE row, so reviving a
-- 2-project row for a shop midway through a Business month would silently downgrade a
-- paying customer — the same trap grantFreeTier guards against in Java.
WITH revivable AS (
    SELECT DISTINCT ON (s.user_id) s.id
      FROM subscriptions s
     WHERE s.plan = 'FREE'
       AND s.status IN ('EXPIRED', 'CANCELLED', 'COMPLETED')
       AND NOT EXISTS (
             SELECT 1
               FROM subscriptions live
              WHERE live.user_id = s.user_id
                AND (live.current_period_start IS NULL OR live.current_period_start <= now())
                AND (live.status = 'ACTIVE'
                     OR (live.status = 'CANCELLED'
                         AND live.current_period_end IS NOT NULL
                         AND live.current_period_end > now())))
     ORDER BY s.user_id, s.created_at DESC
)
UPDATE subscriptions
   SET status                 = 'ACTIVE',
       trial                  = false,
       cancel_at_period_end   = false,
       current_period_start   = now(),
       current_period_end     = now() + interval '1 month',
       projects_used          = 0,
       pdf_downloads_used     = 0,
       carried_project_credits = 0,
       trial_projects_created = 0,
       projects_limit         = 2,
       pdf_downloads_limit    = 5,
       pdf_image_limit        = 4
 WHERE id IN (SELECT id FROM revivable);
