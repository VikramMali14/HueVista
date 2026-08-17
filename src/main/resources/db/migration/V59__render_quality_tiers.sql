-- Three qualities of AI image, at three prices, on three models.
--
-- "One photorealistic image" was never one thing. The models behind it differ by close to
-- an order of magnitude in what they cost to run, and flattening that into a single price
-- meant charging the same for a quick look as for the picture somebody prints and shows
-- their family. A customer choosing between them is also a customer who can have the cheap
-- one when the cheap one is what they want, which the single price never allowed.
--
--   BASIC  1 credit   FLUX.2 Klein, 1K   → falls back to Nano Banana
--   PRO    2 credits  FLUX.2 Pro,   2K   → falls back to Nano Banana Pro
--   MAX    4 credits  FLUX.2 Max,   4K   → falls back to Nano Banana Pro
--
-- The models and the prices are configuration (replicate.render.quality.*,
-- app.ai-credit.render-cost*), so a better model can be promoted into a tier without
-- touching the schema. What lives HERE is the record of which tier an image was actually
-- made at — stored rather than inferred from credits_spent, because the price will move and
-- an image made today has to go on saying what it was for as long as it exists.

ALTER TABLE project_renders
    ADD COLUMN quality character varying(16) NOT NULL DEFAULT 'BASIC';

-- BASIC on every existing row is a statement of fact, not a guess: those renders were all
-- charged one credit (or one project allowance), which is precisely what BASIC means. The
-- column default carries the same reading forward for any client that names no tier, and
-- that is the right default in the other direction too — defaulting to MAX would charge
-- four credits for a request that said nothing.
ALTER TABLE project_renders
    ADD CONSTRAINT project_renders_quality_check
        CHECK ((quality)::text = ANY ((ARRAY['BASIC'::character varying,
                                             'PRO'::character varying,
                                             'MAX'::character varying])::text[]));
