-- Shop-account requests become self-contained: the owner sets their own password
-- and proves their mailbox up front, so provisioning is one click for an admin —
-- and provisions itself if nobody gets to it within a day.
--
-- The password is stored ONLY as a BCrypt hash. Nothing reads it back: it is
-- write-only in the API, excluded from toString(), and copied straight onto the
-- user row at approval without ever being decoded.
ALTER TABLE shop_leads
    ADD COLUMN password_hash character varying(255),
    ADD COLUMN verification_code_hash character varying(255),
    ADD COLUMN verification_expires_at timestamp(6) without time zone,
    ADD COLUMN verification_attempts integer NOT NULL DEFAULT 0,
    ADD COLUMN verification_sent_at timestamp(6) without time zone,
    ADD COLUMN email_verified_at timestamp(6) without time zone,
    ADD COLUMN auto_approve_at timestamp(6) without time zone,
    ADD COLUMN distributor_org_id character varying(255),
    ADD COLUMN created_user_id character varying(255),
    ADD COLUMN approved_at timestamp(6) without time zone,
    ADD COLUMN approved_by_user_id character varying(255);

-- Rows written by the old call-back funnel keep their NEW/CONTACTED/CONVERTED
-- status. They carry no password and no verified address, so isProvisionable()
-- is false for all of them and neither the one-click button nor the 24-hour job
-- will touch them — an admin still creates those the manual way.

-- The queue reads "verified requests waiting", and the hourly job reads "past
-- the deadline"; both are status + timestamp scans.
CREATE INDEX idx_shop_leads_status_auto_approve ON shop_leads (status, auto_approve_at);

-- One live request per mailbox: submitting again while one is pending, and
-- requesting an account for an address that already has a shop, are both
-- refused in the service. This index makes that lookup a seek.
CREATE INDEX idx_shop_leads_email ON shop_leads (email);
