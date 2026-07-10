-- Retailer shade-code scheme: one pattern per shop instead of a custom code
-- per shade.
--
-- The retailer picks up to three parts — a prefix (max 4 chars), a pair (max
-- 2 chars) inserted after the first two characters of the real shade code,
-- and a suffix (max 4 chars). A real code is turned into the customer-facing
-- code as PREFIX + code[0..2] + PAIR + code[2..] + SUFFIX; e.g. shade L124
-- with prefix "AB", pair "XY", suffix "CD" reads ABL1XY24CD. Only the three
-- parts are stored; both directions (encode for display, decode at the
-- counter) are derived. Guests visualising under the shop see the encoded
-- codes, so the shop can read the real shade straight off the customer's
-- screen or PDF without opening the project on the site.

CREATE TABLE shade_code_schemes (
    id character varying(255) NOT NULL,
    organization_id character varying(255) NOT NULL,
    prefix character varying(4) NOT NULL DEFAULT '',
    infix character varying(2) NOT NULL DEFAULT '',
    suffix character varying(4) NOT NULL DEFAULT '',
    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone
);

ALTER TABLE ONLY shade_code_schemes
    ADD CONSTRAINT shade_code_schemes_pkey PRIMARY KEY (id);
ALTER TABLE ONLY shade_code_schemes
    ADD CONSTRAINT uk_shade_code_schemes_org UNIQUE (organization_id);
ALTER TABLE ONLY shade_code_schemes
    ADD CONSTRAINT fk_shade_code_schemes_org FOREIGN KEY (organization_id) REFERENCES organizations(id);
