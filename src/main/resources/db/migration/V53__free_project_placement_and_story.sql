-- The free-project library feeds two pages now, not one.
--
-- Publishing a room has always meant one thing: it appears on /gallery. That was
-- the only public surface the library had, so "published" and "on the gallery"
-- were the same fact and one boolean carried both. There is a second surface —
-- /work, the portfolio — and until now it was a hand-written TypeScript file on
-- the frontend holding twelve invented rooms. Nothing an admin published could
-- reach it, and nothing on it was real.
--
-- So: a placement column saying which page each room belongs on, and the handful
-- of editorial fields the portfolio page needs and the gallery grid does not.
--
-- EXISTING ROWS GET 'GALLERY'. They were published under the old meaning, they
-- are on the gallery today, and a migration must not quietly move somebody's
-- shelf to a different page. New rooms default to WORK in application code
-- (TemplatePlacement.DEFAULT), which is the destination the admin console now
-- opens on — the column default below is only the safety net for a row inserted
-- without one.
ALTER TABLE free_project_templates
    ADD COLUMN placement character varying(16) DEFAULT 'GALLERY' NOT NULL;

ALTER TABLE free_project_templates
    ADD CONSTRAINT free_project_templates_placement_check
        CHECK ((placement)::text = ANY ((ARRAY['GALLERY'::character varying,
                                               'WORK'::character varying,
                                               'BOTH'::character varying])::text[]));

-- Editorial copy for /work. Every one of these is optional: a room can go on the
-- portfolio with none of them set, and the page falls back to what it can read
-- off the room itself — the shades actually on its walls, its room type, and the
-- month it was published. These columns exist to say the things the photograph
-- cannot.
ALTER TABLE free_project_templates
    -- "Pune", "Bengaluru".
    ADD COLUMN location character varying(120),
    -- Text, not a number: "2026" and "Winter 2025" are both wanted.
    ADD COLUMN project_year character varying(16),
    -- The attribution line — "Previewed at the counter · Pune".
    ADD COLUMN credit character varying(200),
    -- One sentence, used as the card summary and the page lead.
    ADD COLUMN blurb character varying(400),
    -- The full story; paragraphs separated by blank lines.
    ADD COLUMN story text,
    -- The stat row: one "Label: Value" per line. Free text rather than its own
    -- table because nothing in the system reads it — it is display copy, and a
    -- table would buy referential integrity over three strings the admin
    -- rewrites at will.
    ADD COLUMN stats text;

-- The listing index named (published, space, room_key, display_order) and every
-- public read now also filters on placement. Replaced rather than added beside:
-- the old one is a strict prefix of this one, so keeping both would cost writes
-- to serve nothing the new index does not already serve.
DROP INDEX IF EXISTS idx_free_project_templates_listing;

CREATE INDEX idx_free_project_templates_listing
    ON free_project_templates (published, placement, space, room_key, display_order);
