-- A mask that never came out no longer throws the cleaned photo away — and a way to
-- rehearse that, and the clean failure before it, without waiting for a real outage.

-- 1. The run got its cleaned canvas but wall detection produced nothing usable.
--
-- This used to be status = FAILED, stage = MASK: the project was dead, and the only
-- thing offered was "report it". But the expensive half had SUCCEEDED — the photo was
-- cleaned, repainted and paid for — and marking three walls by hand takes about a
-- minute with the tool that is already in the studio. So the project now comes back
-- SEGMENTED with no auto regions (the same shape a MANUAL-mode run has), and this
-- column is what separates "the AI missed, mark them yourself" from "the user chose
-- to draw them". It is a fact about ONE run: the next run clears it.
ALTER TABLE projects ADD COLUMN IF NOT EXISTS auto_mask_failed BOOLEAN NOT NULL DEFAULT FALSE;

-- 2. ADMIN testing knob, per segmentation run: make the image models decline.
--
-- NONE / CLEAN / MASK / BOTH. NULL means nothing was asked for and the deployment-wide
-- huevista.testing.simulate-ai-failure setting decides; an explicit NONE forces an
-- honest run on a box where that setting is on. Persisted rather than carried in the
-- queue payload for the same reason skip_image_clean is: the worker that reads it may
-- be a different JVM than the one that took the request.
ALTER TABLE projects ADD COLUMN IF NOT EXISTS simulated_failure VARCHAR(16) NULL;

ALTER TABLE projects DROP CONSTRAINT IF EXISTS chk_projects_simulated_failure;
ALTER TABLE projects ADD CONSTRAINT chk_projects_simulated_failure
    CHECK (simulated_failure IS NULL
           OR simulated_failure IN ('NONE', 'CLEAN', 'MASK', 'BOTH'));

-- 3. Reports the pipeline filed for itself.
--
-- The mask-report queue was built for the failure only a human can see: a run that
-- returns SEGMENTED with the walls in the wrong places. The fallback above adds one
-- the BACKEND can see — detection produced nothing at all — and it must not depend on
-- the user reporting it, because the user now has a working room and no reason to.
-- The pipeline raises it against the project's owner; this flag keeps the queue honest
-- about which reports came from a person.
ALTER TABLE mask_reports ADD COLUMN IF NOT EXISTS auto_raised BOOLEAN NOT NULL DEFAULT FALSE;
