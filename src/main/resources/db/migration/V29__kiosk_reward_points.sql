-- Kiosk: flat platform price + closed-loop shop reward points, replacing the
-- retailer revenue share and its manual UPI payout queue.
--
-- The share model made every kiosk sale a payment collected on the shop's behalf and
-- settled out by bank transfer, which is a regulated pattern (Razorpay Route territory)
-- and would not clear an activation review. The kiosk now sells at one platform price
-- that is entirely HueVista's, and the shop earns points in its owner's billing wallet
-- instead — spendable on images, auto-masks and projects, never withdrawable.

-- 1. The retailer's share becomes the points a sale awarded. Same units (paise), new
--    meaning: this column is now the audit trail for a wallet credit, not a balance
--    anyone draws from.
ALTER TABLE store_payments RENAME COLUMN retailer_share_paise TO bonus_points_paise;

-- 2. Shops no longer price their own link.
ALTER TABLE store_links DROP COLUMN price_paise;

-- 3. The cash exit. Dropped rather than retired in place: this is the whole point of the
--    change, and leaving the table invites the endpoints growing back.
DROP TABLE IF EXISTS wallet_redemptions;
