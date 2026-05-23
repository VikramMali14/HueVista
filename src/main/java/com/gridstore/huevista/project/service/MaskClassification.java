package com.gridstore.huevista.project.service;

import java.util.List;

/**
 * Claude's verdict on the Set-of-Mark composite: which numbered candidate
 * masks belong to each paint category. Indices are 1-based to match the
 * labels drawn on the composite.
 *
 * Every visible mask should be categorized into ONE of:
 *   - mainWall
 *   - accentWall
 *   - trim
 *   - exclude  (windows, doors, stone, brick, AC units, fixtures, etc.)
 *
 * Any candidate Claude doesn't list anywhere is treated as "uncertain" by
 * the segmenter and added to mainWall as a default — favoring inclusion
 * over a missed wall fragment, which is what the user actually wants for
 * a paint visualization.
 *
 * Empty lists are valid (e.g. a photo with no accent wall returns
 * accent_wall=[]).
 */
public record MaskClassification(
        List<Integer> mainWall,
        List<Integer> accentWall,
        List<Integer> trim,
        List<Integer> exclude,
        boolean paintable,
        String wallMaterial,
        String notes
) {
    public boolean anyAssigned() {
        return !mainWall.isEmpty() || !accentWall.isEmpty() || !trim.isEmpty();
    }
}
