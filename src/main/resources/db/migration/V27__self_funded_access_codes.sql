-- Codes the END CUSTOMER paid for, rather than the shop.
--
-- Two quite different things had been sharing one row shape. A counter-issued code is
-- the shop spending its own plan: it reserves an image credit per assigned project at
-- generation time, and every run under it draws on the shop's monthly quota. A KIOSK
-- code is the opposite transaction — a walk-in pays at the public store link and buys
-- their own project outright, and the shop's plan is not part of the deal at all.
--
-- Treating the second like the first produced a flow that could take money and then
-- refuse to deliver: `createOrder` checked only that the kiosk link was switched on, so
-- the customer paid, got a code, uploaded a photo, and was met with a 402 because the
-- shop's subscription had lapsed or its monthly images were spent. Nothing refunded it;
-- StorePayment.reversed was never set on that path. And when the shop DID have quota,
-- the run silently ate a credit the shop had not agreed to spend on a stranger.
--
-- `self_funded` marks the second kind. Runs under such a code skip the shop's quota gate
-- and the shop's charge entirely — in both directions, so the shop is neither a blocker
-- nor a payer.
ALTER TABLE customer_access_codes
    ADD COLUMN self_funded boolean DEFAULT false NOT NULL;

-- Colour-board PDFs are part of what the kiosk customer bought, so they cannot come out
-- of the shop's monthly PDF allowance either. A self-funded code carries its own small
-- allowance instead: one board per project it paid for, counted here. Left at zero and
-- unused for ordinary shop-issued codes, which keep billing to the shop's plan.
ALTER TABLE customer_access_codes
    ADD COLUMN pdf_downloads_used integer DEFAULT 0 NOT NULL;

-- The daily sweep that returns dead image holds now looks at EVERY expired code, not
-- just unredeemed ones, so it reads (expires_at, reserved_images) with the two
-- already-indexed null checks as filters. Without this it is a full scan of the table
-- once a day, growing with every code ever issued.
CREATE INDEX idx_access_codes_expired_holds ON customer_access_codes (expires_at)
    WHERE reserved_images > 0 AND revoked_at IS NULL AND quota_released_at IS NULL;
