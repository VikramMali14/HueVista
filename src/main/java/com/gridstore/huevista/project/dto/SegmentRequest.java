package com.gridstore.huevista.project.dto;

import lombok.Data;

/**
 * Optional body for POST /api/projects/{id}/segment. All fields are optional —
 * the endpoint keeps accepting an empty body for the normal flow. Every field
 * is an ADMIN-only testing knob, ignored for other callers.
 */
@Data
public class SegmentRequest {
    /**
     * false skips the image-cleaner step (Nano Banana clutter removal /
     * repaint) for this run, so masks are generated straight from the
     * original photo. Null or true keeps the default behaviour (the cleaner
     * runs when globally enabled).
     */
    private Boolean cleanImage;

    // Per-run mask enhancement steps (see MaskEnhancement). A project that
    // never received an admin choice runs the default set (just the
    // fixture-protecting colour gate); a submitted body replaces it with
    // exactly the flags set true — so sending everything false gives fully
    // raw masks.

    /** Trim mask pixels that aren't freshly painted surface on the CLEANED
     *  canvas (railings/doors/glass). No-op when cleaning was skipped. */
    private Boolean colourGate;
    /** Morphological cleanup: despeckle + fill pinholes. */
    private Boolean morphClean;
    /** Straighten the model's wobbly boundaries onto polygon segments. */
    private Boolean straighten;
    /** Snap mask boundaries to the canvas's real edges. */
    private Boolean edgeSnap;
    /** Fill the unpainted ribbons between adjacent region masks. */
    private Boolean closeSeams;
}
