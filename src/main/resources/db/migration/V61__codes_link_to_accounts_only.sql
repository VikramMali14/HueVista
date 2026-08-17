-- Access codes belong to accounts, and a redeemed code never expires.
--
-- Three rules replace the old model:
--   1. A code may only be redeemed onto a CUSTOMER account that already exists.
--      Nothing auto-provisions an account, and there is no anonymous guest route.
--   2. An UNREDEEMED code lapses 30 days after it was issued. A REDEEMED one has no
--      deadline at all — the projects it bought are the customer's from then on.
--   3. Image credits are spent when the code is issued and are never returned. An
--      assigned project the customer never creates is lost, not refunded.
--
-- Existing passwordless access-code accounts are deleted outright (hard cut), along
-- with everything hanging off them. Anything they owned goes with them.

-- ── 1. What this migration removes ────────────────────────────────────────────
-- Collected first: the users table is edited last, so the id list has to be stable
-- while the children are removed.
CREATE TEMPORARY TABLE _doomed_users AS
SELECT id FROM users WHERE provider = 'ACCESS_CODE';

-- Every project that ends up ownerless: the ones those accounts own, plus the ones an
-- anonymous guest session owned outright (user_id IS NULL). A guest project has no
-- account to move to and no way for anyone to reach it again, so it goes the same way
-- — and collecting both here keeps one list of per-project children instead of two
-- that have to be kept in step.
CREATE TEMPORARY TABLE _doomed_projects AS
SELECT id FROM projects
 WHERE user_id IN (SELECT id FROM _doomed_users)
    OR user_id IS NULL;

-- ── 2. Per-project children ───────────────────────────────────────────────────
-- regions and paint_jobs hold projects with a plain (NO ACTION) foreign key, so they
-- have to go first or the project delete is refused. project_pdf_pages,
-- project_renders and mask_reports are ON DELETE CASCADE and clear themselves —
-- project_pdf_page_shades follows its page the same way.
DELETE FROM regions    WHERE project_id IN (SELECT id FROM _doomed_projects);
DELETE FROM paint_jobs WHERE project_id IN (SELECT id FROM _doomed_projects);
DELETE FROM projects   WHERE id         IN (SELECT id FROM _doomed_projects);

-- ── 3. Per-account children ───────────────────────────────────────────────────
-- Everything here holds users with a plain foreign key and would otherwise refuse the
-- account delete. project_credits and project_grants cascade on their own.
--
-- uploaded_images comes after the projects above on purpose: projects.image_id is NOT
-- NULL and does not cascade, so an image cannot go before the project holding it.
DELETE FROM paint_jobs
 WHERE customer_id IN (SELECT id FROM _doomed_users)
    OR painter_id  IN (SELECT id FROM _doomed_users);

-- A thread's messages hold the thread, so they go first.
DELETE FROM support_messages
 WHERE conversation_id IN (SELECT id FROM support_conversations
                            WHERE user_id IN (SELECT id FROM _doomed_users));
DELETE FROM support_conversations WHERE user_id IN (SELECT id FROM _doomed_users);

DELETE FROM subscriptions         WHERE user_id          IN (SELECT id FROM _doomed_users);
DELETE FROM customer_entitlements WHERE customer_user_id IN (SELECT id FROM _doomed_users);
DELETE FROM refresh_tokens        WHERE user_id          IN (SELECT id FROM _doomed_users);
DELETE FROM uploaded_images       WHERE user_id          IN (SELECT id FROM _doomed_users);

-- A mask report is about a project, not about the account that filed it. Reports on the
-- projects above are already gone with them; a report left here is one filed against a
-- project that survives, so it survives too and only loses its author. Both columns are
-- nullable, and these accounts were never the staff who resolve reports.
UPDATE mask_reports SET user_id = NULL
 WHERE user_id IN (SELECT id FROM _doomed_users);
UPDATE mask_reports SET resolved_by_user_id = NULL
 WHERE resolved_by_user_id IN (SELECT id FROM _doomed_users);
UPDATE support_conversations SET assignee_user_id = NULL
 WHERE assignee_user_id IN (SELECT id FROM _doomed_users);

-- Release the codes those accounts consumed before the accounts disappear, so no code
-- is left pointing at a user id that no longer exists.
UPDATE customer_access_codes
   SET used_by_user_id = NULL, used_at = NULL
 WHERE used_by_user_id IN (SELECT id FROM _doomed_users);

DELETE FROM users WHERE id IN (SELECT id FROM _doomed_users);

DROP TABLE _doomed_projects;
DROP TABLE _doomed_users;

-- ── 4. Codes consumed by a guest go back to unredeemed ────────────────────────
-- They were marked used with no user behind them. Whether they are still redeemable is
-- now decided purely by the 30-day rule below.
UPDATE customer_access_codes
   SET used_at = NULL
 WHERE used_by_user_id IS NULL AND used_at IS NOT NULL;

-- ── 5. Drop the columns the old model needed ──────────────────────────────────
ALTER TABLE customer_access_codes DROP COLUMN IF EXISTS valid_days;
ALTER TABLE customer_access_codes DROP COLUMN IF EXISTS reserved_projects;
ALTER TABLE customer_access_codes DROP COLUMN IF EXISTS quota_released_at;
ALTER TABLE customer_access_codes DROP COLUMN IF EXISTS extended_at;
ALTER TABLE customer_access_codes DROP COLUMN IF EXISTS extension_count;
ALTER TABLE customer_access_codes DROP COLUMN IF EXISTS guest_redeemed;

-- A customer's access has no end date any more.
ALTER TABLE customer_entitlements DROP COLUMN IF EXISTS access_expires_at;

-- ── 6. Re-base the redemption deadline on the new 30-day rule ─────────────────
-- Unredeemed codes get 30 days from when they were issued. One already past that is
-- simply no longer redeemable, which is the correct outcome.
UPDATE customer_access_codes
   SET expires_at = created_at + INTERVAL '30 days'
 WHERE used_by_user_id IS NULL;

-- ── 7. Guest projects can no longer exist ─────────────────────────────────────
-- Enforced in the schema now that every project has an owner: the "owner is EITHER a
-- user OR an access code" rule is gone, and access_code_id is only ever the shop link.
ALTER TABLE projects ALTER COLUMN user_id SET NOT NULL;
