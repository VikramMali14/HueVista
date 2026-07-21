-- User hierarchy provenance: who provisioned this account
-- (admin -> distributor -> retailer -> painter). Null for self-signups
-- and for accounts created before the hierarchy existed.

ALTER TABLE users
    ADD COLUMN created_by_user_id character varying(255);

CREATE INDEX idx_users_created_by ON users (created_by_user_id);
