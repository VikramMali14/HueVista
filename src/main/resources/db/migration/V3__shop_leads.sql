-- Shop-account requests captured from the public "bring it to your counter"
-- form. Shops are provisioned by an admin, so the funnel stores a lead the
-- admin queue works through instead of dead-ending on a mailto link.
CREATE TABLE shop_leads (
    id character varying(255) NOT NULL,
    name character varying(255) NOT NULL,
    email character varying(255) NOT NULL,
    phone character varying(255),
    shop_name character varying(255) NOT NULL,
    city character varying(255),
    state character varying(255),
    tier character varying(255),
    notes character varying(2000),
    status character varying(20) NOT NULL,
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone
);

ALTER TABLE ONLY shop_leads
    ADD CONSTRAINT shop_leads_pkey PRIMARY KEY (id);

CREATE INDEX idx_shop_leads_created_at ON shop_leads (created_at DESC);
