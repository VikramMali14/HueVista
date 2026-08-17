-- AI image credits that carry a date, without taking the date off the ones that don't.
--
-- The customer catalogue sells a credit "valid for a year", and a year has to be counted
-- from each purchase rather than from the last thing an account did: somebody who buys in
-- January and again in June holds two batches ageing independently, and only a row per
-- batch can say how many of theirs lapse in March. The wallet holds one balance, which is
-- exactly right for SPENDING — the debit is a conditional UPDATE, so two browser tabs
-- cannot both pass a "you have one left" check against the same credit — and has nowhere
-- to put a date. So the batches live beside it rather than replacing it.
--
-- V52 said, in this schema and in as many words, that credits never expire and that this
-- was deliberate rather than an omission. That promise is kept here and not quietly
-- reversed: a batch with a NULL expires_at never lapses, every credit already in a wallet
-- is backfilled as exactly that, and a shop still buys on those terms. Only what is sold
-- WITH a date printed on the line gets one.

-- ---------------------------------------------------------------------------
-- 1. The batches
-- ---------------------------------------------------------------------------
--
-- credits is what the batch opened with and never moves; credits_remaining is what is left
-- in it. Both, rather than one and a running total, because a statement line has to be able
-- to say "3 of your 5 January credits are left" a year after the fact.
--
-- No foreign key to users, for the same reason the wallet has none: this is a financial
-- record, and deleting an account must not silently delete the evidence of what it bought.
CREATE TABLE ai_credit_lots (
    id                character varying(255) NOT NULL,
    user_id           character varying(255) NOT NULL,
    credits           integer NOT NULL,
    credits_remaining integer NOT NULL,
    expires_at        timestamp(6) without time zone,
    source_reference  character varying(255),
    expired_at        timestamp(6) without time zone,
    created_at        timestamp(6) without time zone,
    CONSTRAINT ai_credit_lots_remaining_check CHECK (credits_remaining >= 0)
);

ALTER TABLE ONLY ai_credit_lots
    ADD CONSTRAINT ai_credit_lots_pkey PRIMARY KEY (id);

-- The spend order, and the only read on the hot path: this account's unspent batches,
-- soonest to lapse first. Nulls sort last in Postgres by default on ASC, which happens to
-- be the rule we want anyway — a batch with no date is the one that can afford to wait.
CREATE INDEX idx_ai_credit_lot_user ON ai_credit_lots (user_id, expires_at);

-- The sweep's read: everything due, across all accounts.
CREATE INDEX idx_ai_credit_lot_expiry ON ai_credit_lots (expires_at);

-- ---------------------------------------------------------------------------
-- 2. Every credit already bought, as a batch that never lapses
-- ---------------------------------------------------------------------------
--
-- The invariant the spend path relies on is that an account's unspent batches sum to its
-- wallet balance. Without this backfill every wallet opened before today would break it on
-- the first spend — the balance would fall and there would be no batch to draw it out of,
-- which the service logs as an error and (correctly) refuses to let stop the render.
--
-- NULL expires_at is not a default chosen for convenience. These credits were sold under
-- "credits never expire"; stamping a year on them here would be changing the terms of a
-- sale that already happened, to the disadvantage of the person who paid.
INSERT INTO ai_credit_lots (id, user_id, credits, credits_remaining, expires_at,
                            source_reference, expired_at, created_at)
SELECT gen_random_uuid()::text, w.user_id, w.balance, w.balance, NULL,
       'migrated', NULL, COALESCE(w.created_at, now())
  FROM ai_credit_wallets w
 WHERE w.balance > 0;

-- ---------------------------------------------------------------------------
-- 3. A statement line for the write-off
-- ---------------------------------------------------------------------------
--
-- EXPIRED is a movement like any other and has to appear on the statement: a customer whose
-- balance dropped overnight is owed a line that says why, and "your credits expired" is the
-- only answer that is both true and actionable. Positive types are money in, negative are
-- money out, and this is the second of the negative ones.
ALTER TABLE ai_credit_transactions
    DROP CONSTRAINT IF EXISTS ai_credit_transactions_type_check;

ALTER TABLE ai_credit_transactions
    ADD CONSTRAINT ai_credit_transactions_type_check
        CHECK ((type)::text = ANY ((ARRAY['PURCHASED'::character varying,
                                          'SPENT_ON_RENDER'::character varying,
                                          'RENDER_REFUNDED'::character varying,
                                          'GRANTED'::character varying,
                                          'EXPIRED'::character varying])::text[]));
