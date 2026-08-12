-- Record WHICH half of the AI run failed, and how big the cleaned canvas is.
--
-- projects.failure_reason already carries a sentence for the person reading it, but
-- the studio now turns a failed run into a "report this" prompt, and the report is
-- only useful to an admin if it names the stage: "the photo came back damaged" and
-- "the walls landed in the wrong places" are different bugs in different models.
-- Recovering that from the English sentence would break the first time the sentence
-- was reworded, so the pipeline records it as a value.
--
-- NULL for every project that failed before this shipped, for successful runs, and
-- for failures that belong to neither stage (a missing Replicate token) — the column
-- is a detail about a failure, not a status of its own.
ALTER TABLE projects ADD COLUMN IF NOT EXISTS failure_stage VARCHAR(16) NULL;

ALTER TABLE projects DROP CONSTRAINT IF EXISTS chk_projects_failure_stage;
ALTER TABLE projects ADD CONSTRAINT chk_projects_failure_stage
    CHECK (failure_stage IS NULL OR failure_stage IN ('CLEAN', 'MASK'));

-- The cleaned canvas's own pixel size, recorded when it is stored.
--
-- Click-to-segment sends SAM a normalised click multiplied by the dimensions of the
-- image it is segmenting. It now segments the CLEANED image — the one the studio is
-- actually displaying, and the one the clean removed the wires and parked cars from
-- — and that image is a generative edit plus a local upscale, so it is a different
-- size from the photo it came from. Without its real size the click lands somewhere
-- else in the frame. NULL means no cleaned canvas (or one stored before this
-- shipped), and the click falls back to the original photo as it always did.
ALTER TABLE projects ADD COLUMN IF NOT EXISTS cleaned_image_width INTEGER NULL;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS cleaned_image_height INTEGER NULL;

-- The same two facts, snapshotted onto the report that complains about the run.
-- mask_reports already snapshots status/mode/regions/cleaned-image for the reason
-- these are here too: re-running segmentation overwrites all of it on the project,
-- so an admin opening the queue a day later would otherwise be reading a different
-- run than the one being reported.
ALTER TABLE mask_reports ADD COLUMN IF NOT EXISTS failure_stage VARCHAR(16) NULL;
ALTER TABLE mask_reports ADD COLUMN IF NOT EXISTS failure_reason TEXT NULL;
