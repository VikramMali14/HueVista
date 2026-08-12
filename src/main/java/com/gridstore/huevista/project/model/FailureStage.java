package com.gridstore.huevista.project.model;

/**
 * Which half of the AI run failed, when a project ends {@link ProjectStatus#FAILED}.
 *
 * <p>{@code failureReason} already says what happened in words, but words are for the
 * person reading them; this is for the code. The studio turns a failure into a prompt
 * to report it, and the report is only useful to an admin if it names the right stage —
 * "the photo came back damaged" and "the walls landed in the wrong places" send them to
 * completely different places. Parsing that back out of an English sentence would break
 * the first time the sentence was reworded.
 *
 * <p>Null on projects that failed before this shipped, and on failures that belong to
 * neither stage (a missing Replicate token, an owner that can't be resolved) — those are
 * ours to fix, not something a user can usefully describe.
 */
public enum FailureStage {

    /** The photo clean-up produced nothing, so wall detection was never run. */
    CLEAN,

    /** The clean landed; the mask model found no usable walls in it. */
    MASK
}
