-- Which walls a room is actually having painted.
--
-- A project's regions were, until now, a flat list of every surface anybody had found or
-- drawn: wall detection returns what it sees, and the Mask Studio adds whatever the user
-- outlines. Both are answers to "what is paintable here", and neither is an answer to
-- "what am I painting" — which is the question the customer actually has. Somebody who
-- marks out ten surfaces to get the shapes right and then wants three of them coloured
-- had no way to say so: every region went into the palettes, every "Apply all" put paint
-- on all ten, and the colour board printed ten rows for a three-colour job.
--
-- The workaround was deletion, and it is a bad one in both directions. Deleting a
-- detected wall cannot be undone without re-running detection, which costs a credit; and
-- a wall left out of THIS scheme is very often wanted back in the next one, which is the
-- whole point of trying combinations.
--
-- So inclusion becomes its own fact, separate from existence. A region that is out of the
-- plan keeps its mask, its shape and its place in the room, and is simply not one of the
-- surfaces being coloured: the suggestion palettes size themselves to the walls that ARE
-- in, "Apply all" lands only on those, and the board prints only those.
--
-- Every existing row is in, which is what they have effectively been all along.

ALTER TABLE regions
    ADD COLUMN in_plan boolean NOT NULL DEFAULT true;
