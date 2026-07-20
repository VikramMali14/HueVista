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
}
