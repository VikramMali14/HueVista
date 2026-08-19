-- Two qualities of AI image instead of three, and both of them named for what they are.
--
-- What changed, and why. The old list read BASIC / PRO / MAX at 1 / 2 / 4 credits, and the
-- top of it was never chosen: four credits is twice the tier below for a difference most
-- people cannot see on a phone, so the line existed mainly to make the other two harder to
-- read. It is gone. What is left is the choice people were actually making, priced as it
-- always was and named in the customer's own words:
--
--   PREMIUM  1 credit   FLUX.2 Klein, 1K   -> falls back to Nano Banana
--   LUXURY   2 credits  FLUX.2 Pro,   2K   -> falls back to Nano Banana Pro
--
-- The models and the prices stay configuration (replicate.render.quality.*,
-- app.ai-credit.render-cost*). What lives HERE is the record of which tier an image was
-- actually made at, and this migration is careful with it: an image is a receipt for money
-- somebody spent, and it has to keep saying what it was bought as.
--
--   BASIC -> PREMIUM   the same picture at the same price. A pure rename.
--   PRO   -> LUXURY    the same picture at the same price. A pure rename.
--   MAX   -> LUXURY    the tier is retired. Its rows move to the dearest tier that still
--                      exists, which is the honest reading: those images were bought as
--                      "the best one available", and LUXURY is now what that means. The
--                      four credits they were charged are unaffected — this column records
--                      the tier, never the amount, and credits_spent keeps that.
--
-- The check constraint has to be dropped BEFORE the rows move: PRO and MAX both land on
-- LUXURY, which the old constraint forbids.

ALTER TABLE project_renders
    DROP CONSTRAINT IF EXISTS project_renders_quality_check;

ALTER TABLE project_renders
    ALTER COLUMN quality DROP DEFAULT;

UPDATE project_renders SET quality = 'PREMIUM' WHERE quality = 'BASIC';
UPDATE project_renders SET quality = 'LUXURY'  WHERE quality IN ('PRO', 'MAX');

-- Anything else — a row written by a build that knew a tier this one does not — reads as
-- the floor rather than failing the migration. Silence has always meant the ordinary
-- picture here, and defaulting the other way would claim an image was dearer than it was.
UPDATE project_renders SET quality = 'PREMIUM' WHERE quality NOT IN ('PREMIUM', 'LUXURY');

ALTER TABLE project_renders
    ALTER COLUMN quality SET DEFAULT 'PREMIUM';

ALTER TABLE project_renders
    ADD CONSTRAINT project_renders_quality_check
        CHECK ((quality)::text = ANY ((ARRAY['PREMIUM'::character varying,
                                             'LUXURY'::character varying])::text[]));
