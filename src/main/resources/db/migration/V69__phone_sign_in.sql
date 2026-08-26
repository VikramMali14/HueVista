-- Signing in with a mobile number.
--
-- Firebase Phone Auth sends the one-time code (this business holds no DLT registration,
-- so our own SmsSender delivers nothing and every SMS flow in the app is dark), the
-- browser proves the number to Google, and the backend trades the resulting ID token
-- for a HueVista session. See auth/service/PhoneAuthService.
--
-- No new column: the number and its verified flag have lived on `users` since the
-- baseline, and the new AuthProvider value PHONE is stored in the existing varchar
-- `provider`. What is new is the QUERY — every phone sign-in resolves an account by
-- (phone_number, phone_verified), which until now was only walked on the rare SMS
-- password reset and had nothing behind it but a sequential scan of every user.
--
-- Partial, because the rows that matter are the verified ones and only those: an
-- unverified number is something a person typed at signup, it proves nothing, and no
-- sign-in will ever match on it. Keeping them out leaves a far smaller index and one
-- that says what it means.
--
-- NOT unique, deliberately. The invariant it would express — one live account per
-- verified number — is real and is now enforced in VerificationService, but a unique
-- index would also have to reckon with soft-deleted rows, which keep their number
-- until the row is purged, and it would fail this migration outright on any existing
-- data that already holds a duplicate. Enforcing it in code fails one verification
-- attempt with an explanation the customer can act on; enforcing it here would fail
-- the deploy.
CREATE INDEX IF NOT EXISTS idx_users_verified_phone
    ON users (phone_number)
    WHERE phone_verified AND deleted_at IS NULL;


-- Widen users_provider_check for the new AuthProvider.PHONE.
--
-- Same shape, and the same reason, as V21 when ACCESS_CODE arrived: the constraint is
-- an explicit allow-list of provider names, and a value the code writes but the
-- constraint does not list fails at INSERT with
--   ERROR: new row for relation "users" violates check constraint "users_provider_check"
-- Nothing catches that in the test suite — tests run Hibernate DDL on H2, which never
-- replays these migrations — so the first phone sign-in in production would be the
-- first anyone heard of it. MigrationsCoverEntitiesTest exists for exactly this and
-- is what flagged it.
ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_provider_check;

ALTER TABLE users
    ADD CONSTRAINT users_provider_check CHECK (
        (provider)::text = ANY ((ARRAY[
            'LOCAL'::character varying,
            'GOOGLE'::character varying,
            'ACCESS_CODE'::character varying,
            'PHONE'::character varying
        ])::text[])
    );
