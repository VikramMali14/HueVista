-- A running project can now say what it is actually doing.
--
-- The AI half of a run is two generative calls behind a queue, and either of them may be
-- answered by the second, third or fourth model in a chain before an image comes back.
-- From the studio all of that looked identical: one spinner reading "Detecting walls…"
-- for anything between forty seconds and eight minutes, with no way to tell a model that
-- was busy from a run that had died. People closed the tab, which is the one thing that
-- actually loses the work.
--
-- So the pipeline writes a short human sentence here every time it moves — "FLUX 2 Pro
-- was busy, trying Nano Banana 2" — and the status endpoint the studio already polls
-- hands it straight back. Deliberately prose rather than a stage enum: FailureStage
-- already covers the machine-readable question ("which half died"), and what this one
-- answers is "why is nothing happening yet", which only ever gets read by a person.
--
-- Nullable, and cleared at the start of every run: it describes the run in flight, not
-- the project. A finished project has nothing to say here and shows the result instead.
ALTER TABLE projects
    ADD COLUMN ai_progress_note text;
