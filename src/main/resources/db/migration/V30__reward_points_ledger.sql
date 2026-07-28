-- Reward points become their own ledger, separate from the prepaid rupee wallet.
--
-- They were briefly held as paise in billing_wallet_transactions, which only worked
-- while a point was worth exactly one paise. It no longer is: points buy at their own
-- prices (40 for an image that costs Rs. 50 in cash) and they expire one year after
-- they are earned, where prepaid money never does. A single balance cannot say which
-- price a purchase paid or which rupees died, so the two are split.

-- 1. Points are counted in whole points now, not paise.
ALTER TABLE store_payments RENAME COLUMN bonus_points_paise TO bonus_points;
-- Rescale the seeded/dev rows that were written in paise (3900 -> 39). Live data does
-- not exist yet; this only keeps a developer's database self-consistent.
UPDATE store_payments SET bonus_points = bonus_points / 100 WHERE bonus_points >= 100;

-- 2. Points are held in dated LOTS, not a running total: expiry is per batch, and only
--    a lot can say which points die on which day. A lot with a NULL expires_at and a
--    negative points_remaining is a refund shortfall being earned back — debt must not
--    age away, which is why it has no date.
CREATE TABLE reward_points_lots (
    id character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL,
    points_earned integer NOT NULL,
    points_remaining integer NOT NULL,
    expires_at timestamp(6) without time zone,
    source_reference character varying(255),
    expiry_warning_sent_at timestamp(6) without time zone,
    expiry_notice_sent_at timestamp(6) without time zone,
    expired_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone,
    CONSTRAINT pk_reward_points_lots PRIMARY KEY (id)
);

-- Balance and spend both read "this shop's live lots, soonest expiry first".
CREATE INDEX idx_reward_points_lot_user ON reward_points_lots (user_id, expires_at);
-- The daily notice/expiry sweep scans by date across all shops.
CREATE INDEX idx_reward_points_lot_expiry ON reward_points_lots (expires_at);

-- 3. The statement the shop reads. Append-only; the lots hold the spending power.
CREATE TABLE reward_points_transactions (
    id character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL,
    points integer NOT NULL,
    type character varying(32) NOT NULL,
    reference character varying(255),
    created_at timestamp(6) without time zone,
    CONSTRAINT pk_reward_points_transactions PRIMARY KEY (id)
);

CREATE INDEX idx_reward_points_txn_user ON reward_points_transactions (user_id, created_at);

-- 4. The wallet's brief stint as a points ledger. No rows exist in practice (the kiosk
--    has never taken a live payment), but drop any that a dev database picked up so the
--    two ledgers cannot double-count the same sale.
DELETE FROM billing_wallet_transactions WHERE type IN ('KIOSK_BONUS', 'KIOSK_BONUS_REVERSAL');
