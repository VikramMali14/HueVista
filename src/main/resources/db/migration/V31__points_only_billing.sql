-- Points become the only balance: the prepaid rupee wallet and every per-item cash
-- checkout are removed.
--
-- The wallet held rupees and bought things at rupee prices; points hold points and buy
-- the same things at point prices. Keeping both meant every purchase had two prices that
-- could drift apart, two balances a shop had to reason about, and — once points became
-- buyable — no answer to which one a purchase should draw from. There is one balance now.
-- Shops top it up with money (one rupee, one point) or earn it at their kiosk.

-- 1. The wallet and its journal.
DROP TABLE IF EXISTS billing_wallet_transactions;
DROP TABLE IF EXISTS billing_wallets;

-- 2. The per-item payment ledger becomes the points-purchase ledger. Same job — one
--    Razorpay payment id, claimed once, so a replayed (order, payment, signature) triple
--    cannot mint a second helping — for the one cash purchase that is left.
ALTER TABLE project_credit_payments RENAME TO points_purchases;
ALTER TABLE points_purchases RENAME COLUMN amount_paise TO amount_paise_tmp;
ALTER TABLE points_purchases ADD COLUMN points integer;
ALTER TABLE points_purchases RENAME COLUMN amount_paise_tmp TO amount_paise;
-- Rows here priced projects and reopens, not points. None exist in practice (no live
-- payments have been taken); backfill defensively so the NOT NULL below cannot fail.
UPDATE points_purchases SET points = 0 WHERE points IS NULL;
ALTER TABLE points_purchases ALTER COLUMN points SET NOT NULL;
ALTER TABLE points_purchases DROP COLUMN IF EXISTS purpose;
ALTER TABLE points_purchases DROP COLUMN IF EXISTS project_id;

-- 3. What a project credit cost is a point count now, not paise. Same column, honest name.
ALTER TABLE project_credits RENAME COLUMN price_paise TO points_spent;
ALTER TABLE projects RENAME COLUMN purchase_price_paise TO purchase_points;

-- 4. Points can be bought, so the journal needs a type for it. Bought and earned points
--    are otherwise identical — same expiry clock, same prices — and the ledger is
--    deliberately unable to tell them apart once credited.
--    (No DDL: reward_points_transactions.type is a varchar, not an enum type.)
