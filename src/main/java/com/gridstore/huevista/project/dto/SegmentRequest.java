package com.gridstore.huevista.project.dto;

import lombok.Data;

/**
 * Optional body for POST /api/projects/{id}/segment. All fields are optional —
 * the endpoint keeps accepting an empty body for the default flow (AI clean-up
 * followed by AI auto-masking).
 */
@Data
public class SegmentRequest {
    /**
     * ADMIN-only testing knob (ignored for other callers): false skips the
     * image-cleaner step (Nano Banana clutter removal / repaint) for this run,
     * so masks are generated straight from the original photo. Null or true
     * keeps the default behaviour (the cleaner runs when globally enabled).
     */
    private Boolean cleanImage;

    /**
     * Available to every signed-in caller: how walls are created AFTER the
     * compulsory AI photo clean-up.
     * <ul>
     *   <li>{@code "AUTO"} (or null) — AI wall detection runs and consumes one
     *       auto-mask credit from the plan (rejected up-front with 402
     *       {@code AUTO_MASK_UNAVAILABLE} when the plan has none left / none at
     *       all).</li>
     *   <li>{@code "MANUAL"} — the run stops after the clean-up; the user marks
     *       walls themselves with click-to-segment / hand-drawing (free,
     *       unlimited on every tier).</li>
     * </ul>
     * Persisted on the project so a retry keeps the same choice.
     */
    private String maskMode;

    /**
     * ADMIN-only testing knob (ignored for other callers): make the image models
     * decline for one half of this run, so the recovery paths can be walked through
     * on demand instead of being waited for.
     *
     * <ul>
     *   <li>{@code "CLEAN"} — every cleaning provider "declines", so the run fails at
     *       the clean stage and no masks are generated.</li>
     *   <li>{@code "MASK"} — the clean lands, wall detection produces nothing: the
     *       project comes back on its cleaned canvas with no walls, the studio asks
     *       the user to mark them by hand, and a report is filed with the admin.</li>
     *   <li>{@code "BOTH"} — both of the above (the clean fails first, so this is
     *       what a totally unavailable Nano Banana looks like).</li>
     *   <li>{@code "NONE"} — force an honest run, overriding a deployment-wide
     *       {@code huevista.testing.simulate-ai-failure} setting.</li>
     * </ul>
     *
     * Null leaves the deployment-wide setting in charge. Persisted on the project so
     * the async worker sees it. See {@code AiFailureSimulator}.
     */
    private String simulateFailure;

    /**
     * ADMIN-only testing knob (ignored for other callers): the Replicate model to run
     * THIS run's photo clean-up on, instead of {@code replicate.image-cleaner.model}.
     *
     * <p>Must be one of the ids in {@code AiModelCatalogue} — anything else is refused
     * with 400 rather than passed on to Replicate. Null or blank (the normal case)
     * leaves the configured model in charge.
     *
     * <p>An override also NARROWS the run to that one model: the usual hierarchy
     * (Google's own API, then a different family) is not walked, because a comparison
     * is worthless if the image might have come from a model other than the one being
     * compared. A model that declines simply fails the clean.
     *
     * <p>Persisted on the project so the async worker — possibly another JVM — sees it.
     */
    private String cleanModel;

    /**
     * ADMIN-only testing knob (ignored for other callers): the Replicate model to
     * generate THIS run's colour-coded wall mask with, instead of
     * {@code replicate.nano-banana.model}.
     *
     * <p>Same catalogue and the same persistence as {@link #cleanModel}. Set separately
     * from it on purpose: the two jobs suit different models — the clean rewards a model
     * that holds architecture still, the mask rewards one that fills flat colour to an
     * edge — and holding one stage fixed while the other changes is the only way to see
     * which half of a bad result belongs to which model.
     */
    private String maskModel;
}
