-- Let an admin run ONE project's clean-up and wall detection on a named image model,
-- so two models can be compared on the same photo.
--
-- Both stages already read their model from configuration
-- (replicate.image-cleaner.model, replicate.nano-banana.model) so a newer tier can be
-- swapped in without a deploy. That is the right granularity for production and the
-- wrong one for testing: comparing Nano Banana Pro against FLUX 2 Max meant editing the
-- config, restarting, uploading the photo again, and comparing against a memory of the
-- previous result. These two columns move the same choice down to a single run.
--
-- NULL — the overwhelmingly normal case — means the configured model is used. A value
-- is always one of the ids in AiModelCatalogue: the request is refused before it
-- reaches the queue otherwise, so nothing here is ever a free-text Replicate path.
--
-- Persisted rather than carried in the queue payload for the same reason
-- skip_image_clean and simulated_failure are: the worker that reads them may be a
-- different JVM than the one that took the request, and the choice has to survive a
-- requeue.
ALTER TABLE projects ADD COLUMN IF NOT EXISTS clean_model VARCHAR(128) NULL;
ALTER TABLE projects ADD COLUMN IF NOT EXISTS mask_model VARCHAR(128) NULL;
