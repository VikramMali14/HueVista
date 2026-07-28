-- Whether a shop shows paint NAMES alongside its shade codes.
--
-- A shop running its own code scheme is usually hiding which company a colour comes
-- from. Printing "Asian Paints Ivory Mist" next to the coded number hands that back —
-- the customer searches the real product in seconds — so the shop can drop names
-- everywhere the colour appears: studio, PDF board, share link, kiosk.
--
-- Defaults to true so every existing shop keeps showing names until it says otherwise.
-- It sits on the organization, not on shade_code_schemes, because that row is deleted
-- when the pattern is cleared and a shop may want names hidden with no pattern at all.

ALTER TABLE organizations
    ADD COLUMN show_shade_names boolean DEFAULT true NOT NULL;
