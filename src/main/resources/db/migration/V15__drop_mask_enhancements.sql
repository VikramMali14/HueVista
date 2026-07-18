-- Mask post-processing was removed: region masks are now always stored raw,
-- exactly as the model produced them, so the per-project enhancement choice
-- (the studio's admin testing panel) has nothing left to control.
ALTER TABLE projects DROP COLUMN mask_enhancements;
