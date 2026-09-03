package com.gridstore.huevista.project.dto;

/**
 * The ways of turning a cleaned photo into a mask that the lab can run.
 *
 * <p>They differ in one thing that matters more than accuracy: whether the model
 * REDRAWS the photo or READS it. A redrawn output only resembles its input, so
 * every boundary in it lands a little off and has to be registered back —
 * which is what {@code MaskAligner} and the align bench exist to do. An output
 * read from the pixels is aligned by construction, and there is nothing to
 * register.
 */
public enum MaskLabApproach {

    /**
     * What the pipeline ships today: ask an image model to repaint the photo
     * into flat category colours, then split that image by colour.
     *
     * <p>Redraws. Understands what a wall IS, which is why it is here at all —
     * it is the only approach that separates main wall from trim without being
     * told where either one is. It is also the source of the drift.
     */
    GENERATIVE,

    /**
     * Read the surfaces the CLEAN already repainted, straight out of the cleaned
     * photo. No model, no network, no cost, and no drift — the mask comes from
     * the same pixels the studio paints.
     *
     * <p>This is possible only because of a decision made elsewhere: the cleaner
     * repaints every paintable surface a known flat colour (walls and trim both
     * {@code #ffffff}, door leaves brown, railings charcoal) so the cleaned photo
     * can act as an illumination map. That makes "which pixels are paintable"
     * answerable by colour alone.
     *
     * <p>What it cannot do is separate WALL from TRIM, because the clean paints
     * them the same white on purpose. It answers where the paint boundary is,
     * exactly; it does not answer which surface is which.
     */
    PAINTED_SURFACE,

    /**
     * SAM 2, prompted with clicked points. Traces the real boundary in the real
     * pixels, so it is exact — the same engine the studio's click-to-segment
     * already uses.
     *
     * <p>Needs to be told where to click, and returns no category. One point,
     * one surface.
     */
    SAM_POINTS,

    /**
     * Any Replicate model, with its input body supplied by hand.
     *
     * <p>Here so the lab is not limited to the approaches somebody has already
     * written an adapter for. The interesting candidates — semantic segmenters
     * trained on ADE20K, facade parsers, text-grounded segmenters — each want a
     * different request body, and pinning those into code would mean a deploy
     * per model tried. The admin pastes the body instead, and the lab reports
     * whatever came back.
     */
    CUSTOM_REPLICATE
}
