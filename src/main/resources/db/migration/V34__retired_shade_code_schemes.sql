-- Keep every shade-code pattern a shop stops using, so codes it already issued stay
-- readable.
--
-- A shop's numbering does not live only in this database: it is printed on colour boards,
-- quoted on estimates, sent over WhatsApp and photographed off the counter screen. The
-- checker decoded with the CURRENT pattern alone, so the day a shop changed its prefix
-- every code it had ever handed out started answering "not a valid code" — including to
-- the shop's own staff, holding a card the shop itself had printed.
--
-- Retiring the old row here rather than overwriting it makes those codes decodable for
-- good. Nothing is ever ENCODED from this table: new codes always use the live scheme, so
-- this is strictly a decode-side record and can never make two shades share a code.
CREATE TABLE retired_shade_code_schemes (
    id              varchar(255) PRIMARY KEY,
    organization_id varchar(255) NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    prefix          varchar(4)   NOT NULL DEFAULT '',
    infix           varchar(2)   NOT NULL DEFAULT '',
    suffix          varchar(4)   NOT NULL DEFAULT '',
    retired_at      timestamp    NOT NULL DEFAULT now()
);

CREATE INDEX idx_retired_scheme_org ON retired_shade_code_schemes (organization_id);
