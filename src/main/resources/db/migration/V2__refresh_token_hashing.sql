-- ============================================================================
-- V2  Refresh-token hardening
-- ----------------------------------------------------------------------------
-- 1. Adds refresh_tokens.used_at: rotation now marks a token as consumed
--    (kept briefly for the parallel-refresh grace window + theft detection)
--    instead of deleting the row. Fresh databases already get the column from
--    V1, hence IF NOT EXISTS.
-- 2. Clears the table: rows written before this release hold RAW token values,
--    which the application no longer looks up (it now stores/queries SHA-256
--    hashes only). They can't be hashed retroactively without pgcrypto, so they
--    are dropped — every signed-in user simply logs in once more.
-- ============================================================================

ALTER TABLE refresh_tokens ADD COLUMN IF NOT EXISTS used_at timestamp(6) with time zone;

TRUNCATE TABLE refresh_tokens;
