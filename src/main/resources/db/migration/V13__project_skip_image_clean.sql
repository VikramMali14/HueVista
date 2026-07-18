-- ADMIN testing knob, set per segmentation request: TRUE = skip the
-- image-cleaner step for the next run. Null = default behaviour.
ALTER TABLE projects ADD COLUMN skip_image_clean boolean;
