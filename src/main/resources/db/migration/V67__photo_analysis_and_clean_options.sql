-- Knowing more about the photo than "inside or outside".
--
-- One Claude Haiku call at upload has always answered one question — is the camera in a
-- room or in front of a building — and that single word steers four things: which
-- cleaning prompt runs, whether the mask segmenter forces an accent wall, whether the
-- sky filter applies, and which palette the project opens in. It is enough to CHOOSE
-- between two prompts. It is not enough to write either of them well: the exterior
-- prompt spends a paragraph on rooflines and parapets a compound wall does not have,
-- and the interior prompt's FINISH rules — written for a room whose walls should end up
-- smooth plaster — are exactly backwards in a bathroom, where tile to head height is a
-- finished material rather than unfinished work.
--
-- So this adds two things.
--
-- On uploaded_images: what a CLOSER look at the photo found. A house type one level
-- finer than indoor/outdoor, and the colour the walls actually are right now. These
-- describe the PHOTO rather than any one run, which is why they live here — a second
-- run of the same image reuses the answer instead of paying for it again. All four
-- columns are null on every existing row and on every new upload: the upload path
-- deliberately still asks only the scene question, so a customer's upload behaves and
-- costs exactly what it did before this shipped. They are filled in later, and only by
-- a run that explicitly asked for the analysis.
--
-- On projects: the ADMIN knobs that shape one run's cleaning prompt. analyse_photo is
-- what buys the extra look; house_type overrides what it found, so the same photo can
-- be run under two types and the prompt clauses compared; clean_furnishing and
-- clean_angle decide what the clean-up does with the furniture and the camera. Every
-- one of them is null by default, and null everywhere means the prompt is assembled
-- byte-for-byte as it was before these columns existed. That is the property worth
-- protecting: this is an admin testing surface first, and nothing about a customer's
-- run changes until somebody deliberately turns a knob.

ALTER TABLE uploaded_images
    ADD COLUMN house_type            varchar(32),
    ADD COLUMN detected_wall_hex     varchar(7),
    ADD COLUMN detected_wall_colour  varchar(64),
    ADD COLUMN detected_trim_hex     varchar(7);

ALTER TABLE projects
    ADD COLUMN analyse_photo    boolean,
    ADD COLUMN house_type       varchar(32),
    ADD COLUMN clean_furnishing varchar(16),
    ADD COLUMN clean_angle      varchar(16);
