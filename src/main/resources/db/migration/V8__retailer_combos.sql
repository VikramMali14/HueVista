-- Retailer-curated three-shade combinations ("shop picks").
--
-- A retailer predefines up to 24 named combinations (scope INTERIOR or
-- EXTERIOR) from their portal. The studio's AI Suggest tab shows them to
-- everyone visualising under that shop: the retailer's own staff, customers
-- holding a valid entitlement, and guests on a shop access code.
--
-- The three shade slots are stored denormalised (code + name + hex) in the
-- studio's palette role order — main wall, accent wall, trim — so a combo
-- keeps rendering exactly as saved even if the shade catalogue is re-imported.

CREATE TABLE retailer_combos (
    id character varying(255) NOT NULL,
    organization_id character varying(255) NOT NULL,
    name character varying(80) NOT NULL,
    scope character varying(20) NOT NULL,
    shade1_code character varying(40) NOT NULL,
    shade1_name character varying(120) NOT NULL,
    shade1_hex character varying(7) NOT NULL,
    shade2_code character varying(40) NOT NULL,
    shade2_name character varying(120) NOT NULL,
    shade2_hex character varying(7) NOT NULL,
    shade3_code character varying(40) NOT NULL,
    shade3_name character varying(120) NOT NULL,
    shade3_hex character varying(7) NOT NULL,
    created_at timestamp(6) without time zone
);

ALTER TABLE ONLY retailer_combos
    ADD CONSTRAINT retailer_combos_pkey PRIMARY KEY (id);
ALTER TABLE ONLY retailer_combos
    ADD CONSTRAINT fk_retailer_combos_org FOREIGN KEY (organization_id) REFERENCES organizations(id);
CREATE INDEX idx_retailer_combos_org_created ON retailer_combos (organization_id, created_at DESC);
