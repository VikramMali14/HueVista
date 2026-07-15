-- Colour-board PDF quota per subscription, mirroring the AI-generation quota:
--   pdf_image_limit     — most coloured snapshots one PDF board may contain
--                         (per document; a browser-memory guard as much as a tier perk)
--   pdf_downloads_limit — PDF downloads per billing cycle (2147483647 = unlimited)
--   pdf_downloads_used  — counter, reset on renewal exactly like ai_generations_used
--
-- Existing rows are backfilled from their plan's base allowance. Quantity-scaled
-- subscriptions (quantity > 1) get the base download allowance too — quantity is
-- not stored, and topping-up on the next renewal beats guessing here.

ALTER TABLE subscriptions
    ADD COLUMN pdf_downloads_used integer NOT NULL DEFAULT 0,
    ADD COLUMN pdf_downloads_limit integer NOT NULL DEFAULT 0,
    ADD COLUMN pdf_image_limit integer NOT NULL DEFAULT 0;

UPDATE subscriptions SET
    pdf_image_limit = CASE plan
        WHEN 'STARTER'      THEN 4
        WHEN 'PROFESSIONAL' THEN 8
        WHEN 'BUSINESS'     THEN 12
        WHEN 'ENTERPRISE'   THEN 16
        ELSE 4 END,
    pdf_downloads_limit = CASE plan
        WHEN 'STARTER'      THEN 20
        WHEN 'PROFESSIONAL' THEN 100
        WHEN 'BUSINESS'     THEN 300
        WHEN 'ENTERPRISE'   THEN 2147483647
        ELSE 20 END;
