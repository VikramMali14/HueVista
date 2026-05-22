package com.gridstore.huevista.project.service;

import java.util.List;

/**
 * Claude's Set-of-Mark verdict: given the original photo overlaid with
 * numbered candidate masks from SAM 2 auto, which numbers belong to each
 * paintable category. Indices are 1-based to match the labels drawn on the
 * composite image (the same numbers a human reading the image would see).
 *
 * Empty lists are valid — a photo may have no accent wall, or no visible
 * trim. {@link #anyAssigned()} tells the caller whether the segmentation
 * produced anything worth saving.
 */
public record MaskClassification(
        List<Integer> mainWall,
        List<Integer> accentWall,
        List<Integer> trim,
        boolean paintable,
        String wallMaterial,
        String notes
) {
    public boolean anyAssigned() {
        return !mainWall.isEmpty() || !accentWall.isEmpty() || !trim.isEmpty();
    }
}
