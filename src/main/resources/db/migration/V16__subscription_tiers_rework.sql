-- Subscription tier rework: pricing moves to Rs. 999 / 2,499 / 4,999 (+18% GST)
-- and the single "AI generations" quota splits in two:
--   ai_generations_*        — now counts IMAGES processed (the AI photo clean-up
--                             is compulsory, so every image consumes one)
--   auto_masks_used/limit   — AI wall-detection runs, consumed only when the shop
--                             picks the automatic mask after clean-up (manual
--                             masking is free/unlimited)
--   purchased_image_credits — pay-per-image overage bought at Rs. 50 + GST once
--                             the monthly image quota is spent; never reset on
--                             renewal (a paid credit doesn't evaporate)
ALTER TABLE subscriptions
    ADD COLUMN auto_masks_used integer NOT NULL DEFAULT 0,
    ADD COLUMN auto_masks_limit integer NOT NULL DEFAULT 0,
    ADD COLUMN purchased_image_credits integer NOT NULL DEFAULT 0;

-- Backfill from each plan's new auto-mask allowance.
UPDATE subscriptions SET auto_masks_limit = CASE plan
    WHEN 'STARTER'      THEN 5
    WHEN 'PROFESSIONAL' THEN 40
    WHEN 'BUSINESS'     THEN 90
    WHEN 'ENTERPRISE'   THEN 2147483647
    ELSE 0 END;

-- Per-project wall-creation choice sent with the segmentation request:
-- 'AUTO' (null = default) runs AI wall detection after the clean-up,
-- 'MANUAL' stops after the clean-up so the user marks walls by hand.
ALTER TABLE projects ADD COLUMN mask_mode character varying(16);
