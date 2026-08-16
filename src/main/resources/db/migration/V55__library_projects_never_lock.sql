-- A room taken off the library shelf never locks.
--
-- The free library exists so anybody signed in can open a finished room and paint it
-- without uploading a photo, waiting for wall detection or spending anything. Every one
-- of those promises held right up to the moment the customer had something to show for
-- it, and then all three of the paid rails closed over a room that had cost nobody
-- anything:
--
--   * A copy is born carrying NO access window at all — no purchase, no plan, no shop
--     code — so ProjectAccessService read it as "your subscription has ended" and served
--     it VIEW-ONLY from the first minute for any account without a live plan. The room
--     opened, the palette did not.
--   * Handing over its one colour board CLOSED it (app.project.colour-boards-per-project
--     defaults to 1), and a closed project is view-only whatever else is covering it.
--   * Getting either of those back was a purchase: ₹9 for a lapsed window, ₹99 for a
--     closed project. Charging ₹99 to carry on painting a room we gave away is the whole
--     bug, and it is worse than an ordinary lock because there was never a transaction to
--     point back at.
--
-- So a copy now records the template it came from, and that one column is what the
-- access rules read: a library room is always FULL, is never closed, and can never be
-- sold a reopen. Nothing else about it changes — it is an ordinary project in every
-- other respect, and a room the account uploaded itself keeps every rule it had.
--
-- A column rather than a boolean flag because the id is worth having on its own: it says
-- WHICH room on the shelf this came from, which is what "127 copies of the Jaipur living
-- room" is counted from today by joining storage keys, and what a future "the template
-- this came from has been refreshed" would need.

ALTER TABLE projects
    ADD COLUMN library_template_id character varying(255);

-- No foreign key, and that is deliberate — the same reasoning FreeProjectLibraryService
-- already deletes templates under. A template can be taken off the shelf while copies of
-- it are open and being worked on, and those rooms must survive it: the copy owns its own
-- rows and merely NAMES the template it came from. A cascade would delete somebody's room
-- because an admin tidied the shelf, and a RESTRICT would stop the tidying.
CREATE INDEX idx_projects_library_template ON projects (library_template_id)
    WHERE library_template_id IS NOT NULL;

-- ---------------------------------------------------------------------------
-- Backfill: the copies that already exist
-- ---------------------------------------------------------------------------
--
-- Every copy shares its template's photo — startCopy writes an uploaded_images row
-- pointing at the template's own storage key rather than uploading anything — so the
-- storage key is exactly the join that identifies one, and it is the same join the admin
-- console already counts live copies with.
--
-- Rooms published from an admin's own project are the one thing this could catch by
-- mistake: the SOURCE project of a template keeps its own separately-stored photo (the
-- publish COPIES the file into free-projects/<slug>/ and mints a new key for it), so its
-- key never matches and the source room is left alone. Correct — that room was uploaded
-- and paid for in the ordinary way.
UPDATE projects p
SET library_template_id = t.id
FROM uploaded_images i
JOIN free_project_templates t ON t.image_storage_key = i.storage_key
WHERE p.image_id = i.id
  AND p.library_template_id IS NULL;

-- The rooms that already closed on their one board reopen. They are the accounts this
-- change is for: they were handed a free room, painted it, took their board, and were
-- quoted ₹99 to keep going. Leaving them closed would fix the rule for everyone except
-- the people it has already been charged to.
--
-- colour_boards_used is deliberately NOT reset. It is a true count of what these rooms
-- handed over, the board cap no longer applies to them, and zeroing it would renumber the
-- boards that follow so a customer's second sheet claimed to be their first.
UPDATE projects
SET closed_at = NULL
WHERE library_template_id IS NOT NULL
  AND closed_at IS NOT NULL;
