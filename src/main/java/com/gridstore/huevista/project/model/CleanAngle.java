package com.gridstore.huevista.project.model;

import java.util.Locale;

/**
 * Which camera the cleaned canvas is photographed from.
 *
 * <p>{@link #AS_SHOT} is what every run has always done and is the default. {@link
 * #BEST_VIEW} asks the image model to re-frame the building, and it is the one option in
 * this pipeline that changes something the customer can check against reality — so it is
 * worth being plain about what it costs before reaching for it:
 *
 * <ul>
 *   <li><b>The model invents what the photo does not show.</b> Turning a facade brings a
 *       side wall into view that was never photographed, so the model draws one. In a
 *       paint visualiser that is a wall the customer may buy twenty litres for.</li>
 *   <li><b>The canvas and the photo stop matching.</b> The studio can show the original
 *       alongside the project. A customer flipping between the two and seeing a
 *       different house is the failure this product can least afford.</li>
 *   <li><b>Masks are generated against whatever canvas comes back.</b> They will be
 *       internally consistent, but they describe the re-framed building rather than the
 *       photographed one, and the accent-wall and sky rules downstream were tuned on
 *       real photographs.</li>
 * </ul>
 *
 * <p>Which is why this is an ADMIN knob, off by default, and why the prompt it selects
 * bounds "best" tightly rather than leaving the model to decide what best means.
 */
public enum CleanAngle {

    /**
     * The photograph's own camera, unchanged. The default: on this path the clean prompt
     * is byte-for-byte what it was before this option existed.
     */
    AS_SHOT,

    /** Re-frame the building to a more flattering view of the same elevation. */
    BEST_VIEW;

    /** The value a run gets when nobody chose one. */
    public static final CleanAngle DEFAULT = AS_SHOT;

    /**
     * @throws IllegalArgumentException on anything that isn't a member. Rejected loudly
     *         for the same reason {@code simulateFailure} is: a typo on a knob whose job
     *         is to change the output would otherwise run the default and look like the
     *         feature does nothing.
     */
    public static CleanAngle parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cleanAngle must be AS_SHOT or BEST_VIEW.");
        }
    }
}
