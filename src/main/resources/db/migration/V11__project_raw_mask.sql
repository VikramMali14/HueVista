-- Raw colour-coded segmentation mask: the model's unprocessed RED/GREEN/BLUE/BLACK
-- output image, kept alongside the processed per-region masks so the admin mask
-- viewer can compare what the model produced against what the fidelity pipeline
-- (colour gate, morph clean, edge snap, seam close) actually stored. Storage KEY,
-- not a URL — presigned fresh on every read like cleaned_image_storage_key.
-- Null for projects segmented before this shipped and for manual-only projects.
ALTER TABLE projects ADD COLUMN raw_mask_storage_key character varying(255);
