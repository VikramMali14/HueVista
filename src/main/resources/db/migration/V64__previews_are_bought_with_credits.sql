-- An AI image is bought with an AI credit. Always, on every room.
--
-- Two things went with that sentence being made true, and they are in this file together
-- because neither is coherent on its own.
--
-- 1. THE INCLUDED IMAGE IS GONE. `projects.renders_allowed` was a per-project entitlement:
--    a room the account bought carried one free image, a room a shop handed a customer
--    carried none, and a library room carried none. So the answer to "what does an AI
--    image cost?" was "it depends how this room was paid for, three payment routes ago" —
--    a rule nobody outside the code could predict, and one that made the product's own
--    headline claim false for exactly the rooms people asked about most. There is one
--    pocket now, the account's AI credit wallet, and a credit works on any room.
--
--    Unspent allowances go with it. Leaving them would keep a second, invisible way to
--    get a picture alive for months on rooms that happen to predate this, which is the
--    thing being removed rather than a grandfathering worth having.
--
-- 2. THE PER-PROJECT CASH TOP-UP IS GONE. The other way `renders_allowed` could go up was
--    a flat Razorpay purchase (`purpose = 'RENDER'`), and with the column dropped there is
--    nothing for it to increment. It had no button anywhere in the product — the render
--    studio has sold AI credits for as long as the wallet has existed — so this removes a
--    live endpoint nobody could reach rather than a way anybody was buying.
--
--    The historical rows STAY, purpose and all. They record money that really moved, the
--    payment audit reads them, and `ProjectPurchase.Purpose.RENDER` is kept in the enum
--    for exactly that reason. Nothing new is ever written with it.
--
-- `projects.renders_used` is untouched and stays. It is a count of images this room has
-- made, which is still true and still worth having; only the entitlement beside it was a
-- rule rather than a fact.

ALTER TABLE projects
    DROP COLUMN IF EXISTS renders_allowed;

-- Which photograph the model was given to paint.
--
-- This was a decision the code made silently: the cleaned image when there was one, the
-- original when there was not. Cleaned is the right default and stays it — the clutter is
-- gone and every paintable surface is flattened to white, so the model tints a neutral
-- wall instead of arguing with the colour already on it.
--
-- It should not have been the only option. The clean-up is itself an AI step, and it
-- sometimes takes something real with it: a picture rail, a texture, a shadow that was the
-- reason for the photograph. Somebody looking at both pictures knows which one is their
-- room better than the pipeline does, and now that every image is bought with their own
-- credit it is plainly their call.
--
-- CLEANED on every existing row is a statement of fact rather than a guess: that is what
-- those renders were handed. A room with no cleaned photo still falls back to its original
-- whichever value is stored, because there the two answers are the same picture.
ALTER TABLE project_renders
    ADD COLUMN source_image character varying(16) NOT NULL DEFAULT 'CLEANED';

ALTER TABLE project_renders
    ADD CONSTRAINT project_renders_source_image_check
        CHECK ((source_image)::text = ANY ((ARRAY['CLEANED'::character varying,
                                                  'ORIGINAL'::character varying])::text[]));
