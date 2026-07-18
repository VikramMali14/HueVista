-- ADMIN testing knob, set per segmentation request: comma-separated
-- MaskEnhancement names to apply to that run's masks. Null = none (raw).
ALTER TABLE projects ADD COLUMN mask_enhancements text;
