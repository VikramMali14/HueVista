-- A room off the library shelf runs the ordinary job: board, close, AI image on credits.
--
-- V55 made a library copy exempt from everything — no board cap, never closed, never
-- view-only — because the paid rails had closed over a room that cost nobody anything and
-- were quoting ₹99 to carry on painting it. That fixed the lock and overshot: it also took
-- away the END of the job. Closing is what leads to the render step, so a room that could
-- not close was stranded one move short of its AI image, with a "Close project" button the
-- server refused.
--
-- The rule now splits along the line that actually matters — who paid for WHAT:
--
--   * Opening the room is free, and stays free. The photograph was already stored and the
--     walls were already detected, so a copy claims no entitlement, reserves no plan
--     credit and spends no points. That is rail 3 of ProjectAccessService, and it is why a
--     customer with no subscription can still paint one.
--   * The colour board comes out of the account's own monthly download allowance, exactly
--     as it does on a room they uploaded, and the project's board cap applies. The last
--     board closes the room.
--   * The AI image is bought with an AI credit. It is the one genuinely expensive thing on
--     a free room, and it is now the only thing on one that is charged for.
--
-- Nothing here re-closes the rooms V55 reopened. Those accounts were handed a free room and
-- then locked out of it once already; doing it again by migration is the same surprise
-- twice. A legacy copy that has already spent its board allowance simply cannot take
-- another one — the ordinary refusal, in the ordinary words — and its owner can press
-- "Close project" whenever they want the AI image, which is the button this change gives
-- them back.

-- ---------------------------------------------------------------------------
-- The included AI image goes back
-- ---------------------------------------------------------------------------
--
-- Copies were born with projects.renders_allowed at its column default of 1 — the included
-- render an account gets on a project it PAID for. On a free room that is a ₹99 picture
-- given away with a photograph that was already on the shelf, which was never the deal;
-- startCopy now writes 0, the same as a room a shop hands a customer.
--
-- Only the UNSPENT one is taken. Clamping to renders_used rather than setting 0 outright is
-- what keeps this honest for the accounts that already made their image: their row keeps
-- allowed = used = 1, so hasRenderLeft() reads false (it did already) and the refund path
-- in ProjectRenderService — which decrements renders_used when a model call fails — still
-- has a spent render to hand back. Setting 0 under a used render would leave allowed
-- BELOW used, and a refund would then quietly credit an image that was never included.
UPDATE projects
SET renders_allowed = renders_used
WHERE library_template_id IS NOT NULL
  AND renders_allowed > renders_used;
