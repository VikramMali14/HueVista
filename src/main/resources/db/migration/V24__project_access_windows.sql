-- Paid project access windows, purchased project credits, and code top-ups.
--
-- Three things arrive together because they are one policy:
--
--  1. A project can now carry a PAID VALIDITY WINDOW of its own. Buying a project
--     without an active subscription opens a 30-day window; when the window runs
--     out the project drops to view-only (the last applied colours stay readable)
--     until it is reopened for a small fee, which adds another 30 days.
--
--     The window PAUSES while its owner holds an active subscription: a subscriber
--     is not spending paid days they don't need, so access_expires_at is cleared and
--     the leftover is parked in access_remaining_seconds. When the subscription ends
--     the window resumes from exactly where it stopped. Storing the remainder rather
--     than a frozen end date is what makes pause/resume idempotent — reconciling
--     twice in the same state is a no-op.
--
--  2. project_credits is the ledger of paid-for-but-not-yet-used projects. It
--     replaces "increment a counter": one row per purchase means the price paid and
--     the project it eventually became are both recoverable, and consumption is a
--     compare-and-set on consumed_at rather than a read-modify-write on a total.
--
--  3. A retailer can now top up a code they already issued — more projects on it, or
--     another 10 days of validity — so the columns that record those top-ups live on
--     customer_access_codes.

ALTER TABLE projects
    ADD COLUMN access_expires_at timestamp(6) without time zone,
    ADD COLUMN access_paused_at timestamp(6) without time zone,
    ADD COLUMN access_remaining_seconds bigint,
    ADD COLUMN purchased_at timestamp(6) without time zone,
    ADD COLUMN purchase_price_paise integer DEFAULT 0 NOT NULL;

-- Only the resume sweep reads this, and only for windows that are actually running.
CREATE INDEX idx_projects_access_expires_at ON projects (access_expires_at)
    WHERE access_expires_at IS NOT NULL;

CREATE TABLE project_credits (
    id character varying(255) NOT NULL,
    user_id character varying(255) NOT NULL,
    price_paise integer DEFAULT 0 NOT NULL,
    valid_days integer DEFAULT 30 NOT NULL,
    source character varying(32) NOT NULL,
    project_id character varying(255),
    consumed_at timestamp(6) without time zone,
    created_at timestamp(6) without time zone
);

ALTER TABLE ONLY project_credits
    ADD CONSTRAINT project_credits_pkey PRIMARY KEY (id);
ALTER TABLE ONLY project_credits
    ADD CONSTRAINT fk_project_credits_user FOREIGN KEY (user_id) REFERENCES users(id) ON DELETE CASCADE;
-- The project a credit became is deliberately ON DELETE SET NULL: deleting a project
-- must not delete the record that it was paid for.
ALTER TABLE ONLY project_credits
    ADD CONSTRAINT fk_project_credits_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE SET NULL;

-- The claim query is "oldest unconsumed credit for this user", so index exactly that.
CREATE INDEX idx_project_credits_unconsumed ON project_credits (user_id, created_at)
    WHERE consumed_at IS NULL;

-- What a project-credit payment actually bought. Reopening an expired project and
-- buying a new one are both one-time payments on the same rail, and only the purpose
-- (plus, for a reopen, the project it targets) tells them apart after the fact.
ALTER TABLE project_credit_payments
    ADD COLUMN purpose character varying(32) DEFAULT 'PROJECT_CREDIT' NOT NULL,
    ADD COLUMN amount_paise integer DEFAULT 0 NOT NULL,
    ADD COLUMN project_id character varying(255);

ALTER TABLE customer_access_codes
    ADD COLUMN extended_at timestamp(6) without time zone,
    ADD COLUMN extension_count integer DEFAULT 0 NOT NULL;
