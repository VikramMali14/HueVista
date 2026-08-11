-- "The AI got this wrong" — reports raised from the studio, worked by the admin.
--
-- The pipeline cannot fail this way on its own. A run that puts the walls in the
-- wrong places still returns SEGMENTED, still writes its regions, and passes every
-- check the backend makes; from here it is indistinguishable from a good run. The
-- only party who can tell the difference is the person looking at their own room,
-- which is why this table exists at all — it is the sole channel through which a
-- silent AI failure becomes a thing anyone knows about.
--
-- OWNERSHIP mirrors `projects` exactly: either a signed-in `user_id` OR, for a
-- walk-in guest working under a shop's code, an `access_code_id`, with the other
-- null. Both are nullable and neither is enforced as "exactly one" by a constraint,
-- for the same reason the projects table doesn't: a guest project is re-pointed to
-- a real account when that guest signs up, and a report already filed against the
-- code should survive that without a migration having an opinion about it.
--
-- The four snapshot columns (project_status, mask_mode, region_count,
-- had_cleaned_image) are not denormalization for speed — they are the record of
-- WHICH RUN was being complained about. The first thing anyone does with a bad mask
-- is re-run segmentation, and that overwrites all four on the project itself, so
-- without the copy the admin opening this queue tomorrow would be looking at a
-- different run than the one that upset the user.
CREATE TABLE mask_reports (
    id character varying(255) NOT NULL,

    project_id character varying(255) NOT NULL,
    -- The reporter. Exactly one of these is set (see above).
    user_id character varying(255),
    access_code_id character varying(255),

    -- Comma-separated MaskReportIssue names. A joined table would buy nothing: the
    -- set is at most three values, is always read whole with its row, and is never
    -- queried across reports. Same shape as projects.share_brands.
    issues text NOT NULL,
    -- What the user typed. Optional — a ticked box on its own is a valid report.
    note text,

    status character varying(32) DEFAULT 'NEW'::character varying NOT NULL,

    -- Snapshot of the reported run (see the note above).
    project_status character varying(32),
    mask_mode character varying(16),
    region_count integer DEFAULT 0 NOT NULL,
    had_cleaned_image boolean DEFAULT false NOT NULL,

    -- Admin side. Internal; never shown to the reporter.
    admin_note text,
    resolved_by_user_id character varying(255),
    resolved_at timestamp(6) without time zone,

    created_at timestamp(6) without time zone,
    updated_at timestamp(6) without time zone,

    CONSTRAINT mask_reports_pkey PRIMARY KEY (id),
    CONSTRAINT mask_reports_status_check CHECK (((status)::text = ANY ((ARRAY['NEW'::character varying, 'IN_REVIEW'::character varying, 'RESOLVED'::character varying])::text[])))
);

ALTER TABLE ONLY mask_reports
    ADD CONSTRAINT fk_mask_reports_project FOREIGN KEY (project_id) REFERENCES projects(id) ON DELETE CASCADE;

ALTER TABLE ONLY mask_reports
    ADD CONSTRAINT fk_mask_reports_user FOREIGN KEY (user_id) REFERENCES users(id);

ALTER TABLE ONLY mask_reports
    ADD CONSTRAINT fk_mask_reports_access_code FOREIGN KEY (access_code_id) REFERENCES customer_access_codes(id);

ALTER TABLE ONLY mask_reports
    ADD CONSTRAINT fk_mask_reports_resolved_by FOREIGN KEY (resolved_by_user_id) REFERENCES users(id);

-- The queue's only read: open reports, newest first.
CREATE INDEX idx_mask_reports_status_created ON mask_reports (status, created_at DESC);

-- Finding a reporter's existing open report on a project, so a second press of the
-- button updates it instead of stacking a duplicate ticket in the queue.
CREATE INDEX idx_mask_reports_project ON mask_reports (project_id);
