-- A way back to the mask the AI actually produced.
--
-- Refining a wall in the Mask Studio overwrites the region's mask and deletes the file
-- it replaced, so wall detection's own outline lasted exactly until the first edit. That
-- is the wrong thing to make permanent: the edits people make most are small and
-- exploratory — nudge an edge, cut a window out, drag the whole mask a few pixels to
-- line it up with the photo — and any one of them can go wrong in a way that is far
-- easier to abandon than to unpick by hand. Undo covers it inside one editing session
-- and nothing covered it afterwards, so a mask that was fine yesterday could only be
-- recovered by re-running detection, which costs a credit.
--
-- So the first hand-edit of a region now files the mask it is replacing here instead of
-- deleting it, and every later edit leaves this column alone. It therefore means one
-- precise thing: the mask as it stood before the user touched it — for a detected wall,
-- detection's own output. Null is the honest answer for every region nobody has edited
-- yet, because there the live mask IS the original and there is nothing to go back to.
--
-- Sized like mask_url, which it holds the same kind of value as: a bare storage key on
-- new rows, a legacy presigned S3 URL on old ones.

ALTER TABLE regions
    ADD COLUMN original_mask_url varchar(2048);
