-- A project gets an ending.
--
-- Until now a project only ever stopped by running out of days. That is a clock, not a
-- conclusion: the work sat there unfinished, the customer had nothing in their hand, and
-- the only thing the product knew about the job was that it had lapsed. What the customer
-- actually leaves with is the COLOUR BOARD — the PDF of their room in a few shades — and
-- the server never learned a thing about one. The download call carried no body at all:
-- no project, no shades, no page count. It charged a plan and returned.
--
-- So this migration adds the two halves of an ending. First, the record of what was handed
-- over: one row per page of every board, with the shades that were on it. Second, closure
-- itself — the customer saying "I'm done", either by pressing the button or by taking the
-- second of their two boards.
--
-- Closing is what unlocks the last step: one photorealistic AI render, in one of the eight
-- combinations they already chose between on paper. After that the project is read-only and
-- only those eight combinations stay visible; the rest of the catalogue closes with it.
-- Reopening is a purchase, and a dearer one than a lapsed window (₹99 against ₹9), because
-- it is asking for a job that already delivered everything it promised to start again.

-- ---------------------------------------------------------------------------
-- 1. Closure, board count and render allowance on the project itself
-- ---------------------------------------------------------------------------
--
-- closed_at is a timestamp beside the access window rather than a new value on
-- ProjectStatus. That enum tracks how far the segmentation pipeline got
-- (CREATED → SEGMENTING → SEGMENTED → FAILED) and answering "is the job finished?" out of
-- the same column would mean a closed project could no longer say whether its masks were
-- ready. sent_to_shop_at, the other lifecycle fact this table records, is a timestamp for
-- exactly the same reason.
--
-- colour_boards_used has to be counted HERE and not on the subscription. The plan's
-- pdf_downloads_used is a monthly total across every room a shop touches, so it can say
-- how much of the month is left but never how many boards THIS job produced — and closing
-- one job off the other's number would close every project a shop opened after its 20th
-- download of the month.
--
-- renders_allowed defaults to 1: every project carries its included render from the day it
-- is created, and spends it only if it closes. Top-ups increment it. Storing the allowance
-- per project rather than as an account balance is deliberate — a render is only meaningful
-- against the eight combos of the project that produced it, so a spare one floating in an
-- account would have nowhere to be spent.
ALTER TABLE projects
    ADD COLUMN closed_at          timestamp(6) without time zone,
    ADD COLUMN colour_boards_used integer NOT NULL DEFAULT 0,
    ADD COLUMN renders_allowed    integer NOT NULL DEFAULT 1,
    ADD COLUMN renders_used       integer NOT NULL DEFAULT 0;

-- Closed projects are read in two places that matter — the dashboard listing and the
-- closing flow — and both ask the same question of one owner's rooms.
CREATE INDEX idx_projects_closed_at ON projects (closed_at) WHERE closed_at IS NOT NULL;

-- ---------------------------------------------------------------------------
-- 2. What was on each colour board
-- ---------------------------------------------------------------------------
--
-- One row per PAGE, which is one coloured version of the room. Two boards of four pages is
-- the eight-combo set the closing flow renders from, and the numbering is deliberately two
-- columns rather than one running index: board_index says which download it came from and
-- page_index where it sat inside that document, so the pair reproduces exactly what the
-- customer was holding.
--
-- Nothing here stores pixels. The selection page re-renders each combo from the cleaned
-- photo, the region masks and the hexes below — all of which already exist and are already
-- served — which stays sharp at any size, costs no storage, and cannot drift out of sync
-- with the masks the way a baked thumbnail would.
CREATE TABLE project_pdf_pages (
    id           character varying(255) NOT NULL,
    project_id   character varying(255) NOT NULL,
    board_index  integer NOT NULL,
    page_index   integer NOT NULL,
    title        character varying(160),
    created_at   timestamp(6) without time zone
);

ALTER TABLE ONLY project_pdf_pages
    ADD CONSTRAINT project_pdf_pages_pkey PRIMARY KEY (id);

-- Deleting a project takes its boards with it. They are a record of what that project
-- handed over and mean nothing without it.
ALTER TABLE ONLY project_pdf_pages
    ADD CONSTRAINT project_pdf_pages_project_fkey
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

-- The only read: every page of one project, in the order the customer saw them.
CREATE INDEX idx_project_pdf_pages_project
    ON project_pdf_pages (project_id, board_index, page_index);

-- One row per painted surface on that page. Denormalised on purpose, the same way
-- retailer_combos stores its three slots: a board is a record of what somebody was handed,
-- so it has to keep saying that after the catalogue is re-imported or the shade retired.
--
-- region_id is a plain integer and NOT a foreign key. A page has to survive its region
-- being redrawn or deleted — the customer still took that board away — and hex_code alone
-- is enough to re-render the combo, so there is nothing to cascade and nothing to null out.
CREATE TABLE project_pdf_page_shades (
    id            bigint NOT NULL,
    page_id       character varying(255) NOT NULL,
    region_id     bigint,
    region_label  character varying(255),
    shade_code    character varying(64),
    shade_name    character varying(160),
    hex_code      character varying(16) NOT NULL,
    display_order integer NOT NULL DEFAULT 0
);

ALTER TABLE project_pdf_page_shades ALTER COLUMN id ADD GENERATED BY DEFAULT AS IDENTITY (
    SEQUENCE NAME project_pdf_page_shades_id_seq
    START WITH 1
    INCREMENT BY 1
    NO MINVALUE
    NO MAXVALUE
    CACHE 1
);

ALTER TABLE ONLY project_pdf_page_shades
    ADD CONSTRAINT project_pdf_page_shades_pkey PRIMARY KEY (id);

ALTER TABLE ONLY project_pdf_page_shades
    ADD CONSTRAINT project_pdf_page_shades_page_fkey
        FOREIGN KEY (page_id) REFERENCES project_pdf_pages(id) ON DELETE CASCADE;

CREATE INDEX idx_project_pdf_page_shades_page
    ON project_pdf_page_shades (page_id, display_order);

-- ---------------------------------------------------------------------------
-- 3. The AI renders
-- ---------------------------------------------------------------------------
--
-- The options are one column each rather than a JSON blob. They are few, they are closed
-- sets, and every one is read back to rebuild the prompt when a render is retried — a shape
-- a query can filter on is worth more here than one that can absorb a field nobody planned.
--
-- storage_key holds a KEY, never a URL. Read paths presign fresh on every response; a
-- presigned URL expires in an hour, so storing one freezes a dead link into the row. This
-- is the same rule regions and cleaned images already follow.
--
-- page_id is nullable and does NOT cascade: a render outlives the board page it came from,
-- because the image is the deliverable and the page was only the brief.
CREATE TABLE project_renders (
    id             character varying(255) NOT NULL,
    project_id     character varying(255) NOT NULL,
    page_id        character varying(255),
    status         character varying(16)  NOT NULL,
    time_of_day    character varying(16)  NOT NULL,
    border_mode    character varying(16)  NOT NULL,
    lighting       character varying(16)  NOT NULL,
    furnishing     character varying(16)  NOT NULL,
    style          character varying(24)  NOT NULL,
    note           character varying(500),
    storage_key    character varying(512),
    failure_reason character varying(500),
    created_at     timestamp(6) without time zone,
    completed_at   timestamp(6) without time zone,
    CONSTRAINT project_renders_status_check
        CHECK ((status)::text = ANY ((ARRAY['QUEUED'::character varying, 'RUNNING'::character varying,
                                            'READY'::character varying, 'FAILED'::character varying])::text[]))
);

ALTER TABLE ONLY project_renders
    ADD CONSTRAINT project_renders_pkey PRIMARY KEY (id);

ALTER TABLE ONLY project_renders
    ADD CONSTRAINT project_renders_project_fkey
        FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY project_renders
    ADD CONSTRAINT project_renders_page_fkey
        FOREIGN KEY (page_id) REFERENCES project_pdf_pages(id) ON DELETE SET NULL;

CREATE INDEX idx_project_renders_project ON project_renders (project_id, created_at DESC);

-- ---------------------------------------------------------------------------
-- 4. Telling the purchases apart
-- ---------------------------------------------------------------------------
--
-- project_purchases used to hold one kind of row: one payment, one extra project. It now
-- carries four — a project, a three-for-two bundle, a reopen and a render top-up — and the
-- amount can no longer distinguish them. A ₹99 row is a closed reopen or a render depending
-- entirely on which was bought, and a receipt nobody can read back is not a record.
--
-- Existing rows are all plain single-project purchases: the other three flows did not exist
-- when they were written, and the reopen rows among them (which passed a null plan) still
-- bought exactly what PROJECT/1 describes as far as the allowance is concerned. Backfilling
-- them to the defaults is therefore not a guess.
ALTER TABLE project_purchases
    ADD COLUMN purpose    character varying(32) NOT NULL DEFAULT 'PROJECT',
    ADD COLUMN credits    integer NOT NULL DEFAULT 1,
    ADD COLUMN project_id character varying(255);

-- No foreign key on project_id, and no cascade. A receipt has to outlive the thing it was
-- for: deleting a room must not quietly delete the record that somebody paid for it.
CREATE INDEX idx_project_purchases_project ON project_purchases (project_id)
    WHERE project_id IS NOT NULL;
