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
}
