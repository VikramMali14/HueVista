package com.gridstore.huevista.project.model;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Optional per-run mask enhancement steps an ADMIN can enable on a
 * segmentation request (studio testing panel). By default NONE run — the
 * stored region masks are exactly the model's raw output, only resized to
 * the canvas. Persisted on the project as a comma-separated list of names
 * so the async worker sees the same choice.
 */
public enum MaskEnhancement {
    /** Drop mask pixels that are clearly not freshly painted surface on the
     *  CLEANED canvas (railings, doors, glass). Needs the cleaned image —
     *  ignored when the run skipped cleaning. */
    COLOUR_GATE,
    /** Morphological cleanup: despeckle + fill pinholes. */
    MORPH_CLEAN,
    /** Collapse the model's hand-painted wobble onto straight polygon
     *  segments ({@link com.gridstore.huevista.project.service.MaskStraightener}). */
    STRAIGHTEN,
    /** Re-attach mask boundaries to the canvas's real edges
     *  ({@link com.gridstore.huevista.project.service.MaskRefiner}). */
    EDGE_SNAP,
    /** Fill the unpainted ribbons between adjacent region masks. */
    CLOSE_SEAMS;

    /** Parses a stored CSV (null/blank/unknown names tolerated) into a set. */
    public static Set<MaskEnhancement> parseCsv(String csv) {
        EnumSet<MaskEnhancement> out = EnumSet.noneOf(MaskEnhancement.class);
        if (csv == null || csv.isBlank()) return out;
        for (String part : csv.split(",")) {
            try {
                out.add(valueOf(part.trim()));
            } catch (IllegalArgumentException ignored) {
                // Unknown name (e.g. a step removed in a later release) — skip.
            }
        }
        return out;
    }

    /** Joins a set into the stored CSV form; null when the set is empty. */
    public static String toCsv(Set<MaskEnhancement> set) {
        if (set == null || set.isEmpty()) return null;
        return set.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
