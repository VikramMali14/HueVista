-- Where an admin put the mask by hand, kept so it can be re-opened and adjusted.
--
-- MaskAligner measures how a generative colour-coded mask sits on the canvas and
-- corrects it, but its search is deliberately timid: capped at 5% of the frame, 6% of
-- the size, 3% per cell of the local field, and every candidate discarded unless it
-- beats leaving the mask alone by a clear margin. That is the right posture for a step
-- nobody is watching — a wrong automatic move is worse than none — and it means the
-- runs it declines are exactly the ones a person has to finish, on the facades where
-- the repaint drifted by more than the search may reach or disagreed with itself across
-- the frame.
--
-- The correction itself needs no new geometry: a hand-made registration is the same
-- scale/offset/lattice the aligner already produces and MaskProcessor already resamples
-- through. What was missing is anywhere to KEEP one. Without this column the only record
-- of the work is the resampled region masks it produced, which cannot be re-opened,
-- nudged a further two pixels, or compared against what the aligner had guessed — so a
-- mask that came out nearly right would have to be re-registered from nothing.
--
-- Held as the JSON the admin bench sends and reads back: scaleX/scaleY, offsetX/offsetY,
-- and, when the frame needed more than one rigid answer, a cols×rows lattice of
-- displacements. Text rather than a set of columns because the lattice's length depends
-- on the grid the person chose, and no query ever looks inside this — it is loaded whole,
-- by one screen, for one project.
--
-- Null is every project nobody has hand-registered, which is almost all of them: the
-- automatic fit is what shipped and there is nothing here to say about it.

ALTER TABLE projects
    ADD COLUMN manual_mask_registration text;
