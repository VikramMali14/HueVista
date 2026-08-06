-- Editable images for the public marketing site.
--
-- The home page's before/after slider is the reason this exists. It proves the
-- whole product — "same room, same light, only the wall colour changed" — and it
-- was proving it with two CSS gradients, because putting a real photograph there
-- meant editing a component, rebuilding and redeploying. Anything that needs a
-- deploy to change is a thing nobody changes.
--
-- One row per SLOT, not per file. A slot is a fixed position in the design
-- ("home.compare.before") that the front end knows how to render; the row says
-- which uploaded file is currently in it. That is why `slot` is the natural key
-- and there is no history table: replacing a slot's image is the whole operation,
-- and the previous file is deleted with it.
--
-- Every slot is optional. A missing row is not an error state — it means the
-- front end draws its built-in default, which is what a fresh install shows and
-- what the site falls back to if an admin clears a slot. The marketing site must
-- never depend on this table having been populated.
--
-- The files live under the shared "site-assets/" storage prefix, like the free
-- project library's, and are served to ANONYMOUS visitors: the home page has no
-- session. That is the one thing that makes these different from every other
-- upload in the system, and it is why nothing user-owned may ever be addressed
-- through the public route that serves them.
CREATE TABLE site_assets (
    -- Slot id, e.g. "home.compare.before". Lower-case dotted path.
    --
    -- The FRONT END owns the registry of which slots exist and what each is for,
    -- because a slot is a position in a layout and adding one always means
    -- writing the markup that draws it. The backend validates the shape of the id
    -- and nothing more: keeping a second list here would buy no safety (only an
    -- admin can write these, and the console offers a fixed set) while making
    -- every new slot a two-service deploy.
    slot character varying(120) NOT NULL,

    storage_key character varying(512) NOT NULL,
    content_type character varying(100) NOT NULL,
    file_size bigint DEFAULT 0 NOT NULL,
    -- Read from the image on upload so the admin page can warn when a picture is
    -- far off the aspect ratio its slot is drawn at, and so the public manifest
    -- can hand the browser real dimensions instead of letting the page reflow.
    width integer,
    height integer,

    -- What the admin actually picked, kept only so the admin page can say
    -- "living-room-after.jpg" rather than a UUID.
    original_filename character varying(255),

    updated_by_user_id character varying(255),
    updated_at timestamp(6) without time zone,

    CONSTRAINT site_assets_pkey PRIMARY KEY (slot)
);

-- The public manifest reads every row in one go and is cached hard at the edge,
-- so there is no access pattern here worth a second index.
