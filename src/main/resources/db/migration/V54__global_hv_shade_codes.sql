-- One permanent, platform-wide code per shade — HV0001, HV0002, … — that any
-- HueVista shop can decode, and nobody else can.
--
-- WHY THIS AND NOT THE SHOP PATTERN WE ALREADY HAVE. shade_code_schemes (V-earlier)
-- lets each shop wrap the manufacturer's own code in a prefix / inserted pair /
-- suffix, so Asian Paints L124 reads ABL1XY24CD to that shop's customers. It hides
-- the company from the customer, which is the point, but it is reversible by anyone
-- who works out the pattern from two codes, and — more to the point here — it can
-- only be read by the shop that invented it. A customer holding a colour board from
-- one shop cannot walk into another and be served, because the second shop has no
-- idea what ABL1XY24CD means. The pattern is per-shop by construction.
--
-- An HV code is the opposite trade. It carries no information at all: HV0348 is a
-- row number, not an encoding, so no amount of staring at a colour board recovers
-- the company or the shade. Any shop with a HueVista account resolves it through
-- the decode endpoint; anyone without one gets nothing, because there is nothing in
-- the string to get. That is what makes "decode at any HueVista shop" true on a
-- printed board, which is the whole reason a customer's colour board is worth
-- carrying out of the door.
--
-- ASSIGNMENT IS THE DATABASE'S JOB, not Java's. Shades are inserted from four
-- different paths (the Asian Paints seeder, the generic brand importer, the admin
-- CSV upload, and single-shade admin creates), and a Java-side allocator would have
-- to be remembered at each one — the fifth path added later is the one that ships a
-- shade with no code. A column DEFAULT drawing from a sequence cannot be forgotten:
-- every insert gets a code whether or not the code that wrote it knows this column
-- exists. The Shade entity carries @DynamicInsert so Hibernate leaves the column out
-- of the INSERT rather than writing an explicit NULL over the default.

CREATE SEQUENCE hv_shade_code_seq START WITH 1 INCREMENT BY 1;

-- Zero-padded to four digits for the ordinary case, and NEVER truncated beyond it.
-- lpad() silently CUTS a string longer than the target width, so a bare
-- lpad(n::text, 4, '0') would turn shade 10 000 into "1000" — a duplicate of shade
-- 1000 — and the catalogue is already ~9.5k shades, i.e. weeks away from that.
-- greatest(4, length(n)) pads the short ones and leaves the long ones whole.
CREATE FUNCTION hv_shade_code(n bigint) RETURNS text
    LANGUAGE sql IMMUTABLE STRICT AS
$$ SELECT 'HV' || lpad(n::text, greatest(4, length(n::text)), '0') $$;

ALTER TABLE shades ADD COLUMN hv_code character varying(12);

-- Backfill in a STABLE order (company, then the manufacturer's own code) rather than
-- by primary key. Ids follow whatever order the seeders happened to run in, which is
-- not reproducible across environments; ordering by the shade's own identity means
-- staging and production land on the same HV code for the same colour, so a board
-- printed against one is readable against the other.
WITH ordered AS (
    SELECT id, row_number() OVER (ORDER BY brand_id, shade_code) AS rn
    FROM shades
)
UPDATE shades s
SET hv_code = hv_shade_code(o.rn)
FROM ordered o
WHERE s.id = o.id;

-- Carry the sequence past everything the backfill just used, so the next inserted
-- shade continues the run instead of colliding with row 1.
SELECT setval('hv_shade_code_seq', GREATEST((SELECT count(*) FROM shades), 1), true);

ALTER TABLE shades ALTER COLUMN hv_code SET DEFAULT hv_shade_code(nextval('hv_shade_code_seq'));
ALTER TABLE shades ALTER COLUMN hv_code SET NOT NULL;

-- Unique because the code IS the identity a customer carries out of the shop: two
-- shades sharing one would make a colour board ambiguous at the counter, which is
-- the single thing this feature must never be. The index also serves the decode
-- lookup, which is the only way anyone reads these.
ALTER TABLE shades ADD CONSTRAINT uq_shades_hv_code UNIQUE (hv_code);
