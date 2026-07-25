-- V20 introduced the passwordless walk-in customer account that is auto-provisioned
-- when a retailer access code is redeemed (AuthProvider.ACCESS_CODE), but the
-- users.provider check constraint still only allowed LOCAL and GOOGLE. Every
-- redemption therefore failed with:
--   ERROR: new row for relation "users" violates check constraint "users_provider_check"
-- Widen the constraint to cover the new provider.

ALTER TABLE users
    DROP CONSTRAINT IF EXISTS users_provider_check;

ALTER TABLE users
    ADD CONSTRAINT users_provider_check CHECK (
        (provider)::text = ANY ((ARRAY[
            'LOCAL'::character varying,
            'GOOGLE'::character varying,
            'ACCESS_CODE'::character varying
        ])::text[])
    );
