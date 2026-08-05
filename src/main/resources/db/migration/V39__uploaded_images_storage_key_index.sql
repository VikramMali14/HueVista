-- Find the image rows that name a given file.
--
-- Free-library templates made this a question worth asking quickly: every copy
-- someone starts shares the template's stored photo, so "how many rows name this
-- key?" is exactly "how many rooms would go blank if I deleted these files?" —
-- the number the admin sees before choosing to purge a template's assets.
--
-- Without an index that count is a sequential scan of every image ever uploaded,
-- once per template on the shelf.
CREATE INDEX idx_uploaded_images_storage_key ON uploaded_images (storage_key);
