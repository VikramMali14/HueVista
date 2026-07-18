package com.gridstore.huevista.project.dto;

import lombok.Data;

/**
 * Optional body for POST /api/projects/{id}/segment. All fields are optional —
 * the endpoint keeps accepting an empty body for the normal flow.
 */
@Data
public class SegmentRequest {
    /**
     * ADMIN-only testing knob: false skips the image-cleaner step (Nano Banana
     * clutter removal / repaint) for this run, so masks are generated straight
     * from the original photo. Null or true keeps the default behaviour (the
     * cleaner runs when globally enabled). Ignored for non-admin callers.
     */
    private Boolean cleanImage;
}
