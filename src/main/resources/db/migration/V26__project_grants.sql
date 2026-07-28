-- The ledger behind "the shop gave this customer another project".
--
-- Granting used to be a bare `allowance + 1` on the customer's entitlement: nothing was
-- reserved, nothing was recorded, and a shop could hand out unlimited projects without
-- their subscription ever noticing. Issuing a CODE reserved image credits properly; the
-- direct grant never did.
--
-- A row per grant fixes both halves of that. It reserves against the shop's quota like
-- code issuance does, and it records enough to take the grant BACK: how many projects,
-- who they went to, and — crucially — which subscription period paid for them.
--
-- The period matters because a refund across a renewal would create quota out of
-- nothing. Images reserved in March came out of March's allowance; releasing them in
-- April would hand April a credit March paid for. So a grant is revocable only while the
-- subscription that funded it is still in the same period: subscription_id AND
-- period_start must both still match. Once the plan renews, the grant is spent, whether
-- or not the customer ever used it.

CREATE TABLE project_grants (
    id character varying(255) NOT NULL,
    retailer_org_id character varying(255) NOT NULL,
    -- Exactly one of these is set: a grant either tops up a customer's allowance
    -- directly, or adds projects to a code they are holding.
    customer_user_id character varying(255),
    access_code_id character varying(255),
    projects integer NOT NULL,
    -- The subscription the images were reserved against, and the period it was in.
    -- Null when the shop had no subscription to reserve from (legacy rows only —
    -- granting now requires one).
    subscription_id character varying(255),
    period_start timestamp(6) without time zone,
    revoked_at timestamp(6) without time zone,
    revoked_by_user_id character varying(255),
    created_at timestamp(6) without time zone
);

ALTER TABLE ONLY project_grants
    ADD CONSTRAINT project_grants_pkey PRIMARY KEY (id);
ALTER TABLE ONLY project_grants
    ADD CONSTRAINT fk_project_grants_org FOREIGN KEY (retailer_org_id) REFERENCES organizations(id) ON DELETE CASCADE;
ALTER TABLE ONLY project_grants
    ADD CONSTRAINT fk_project_grants_customer FOREIGN KEY (customer_user_id) REFERENCES users(id) ON DELETE CASCADE;
ALTER TABLE ONLY project_grants
    ADD CONSTRAINT fk_project_grants_code FOREIGN KEY (access_code_id) REFERENCES customer_access_codes(id) ON DELETE CASCADE;
-- A grant belongs to a customer or to a code, never to both and never to neither.
ALTER TABLE ONLY project_grants
    ADD CONSTRAINT ck_project_grants_target CHECK (
        (customer_user_id IS NOT NULL AND access_code_id IS NULL)
        OR (customer_user_id IS NULL AND access_code_id IS NOT NULL));

-- The list the shop revokes from: this org's grants, newest first.
CREATE INDEX idx_project_grants_org ON project_grants (retailer_org_id, created_at DESC);
-- Reversing a grant starts from its target, so index both ways in.
CREATE INDEX idx_project_grants_customer ON project_grants (customer_user_id)
    WHERE customer_user_id IS NOT NULL;
CREATE INDEX idx_project_grants_code ON project_grants (access_code_id)
    WHERE access_code_id IS NOT NULL;
