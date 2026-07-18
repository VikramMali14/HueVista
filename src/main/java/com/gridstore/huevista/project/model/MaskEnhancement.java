package com.gridstore.huevista.project.model;

import java.util.EnumSet;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * Optional per-run mask enhancement steps an ADMIN can pick on a
 * segmentation request (studio testing panel). When a project has NO stored
 * choice, {@link #defaultSet()} applies: just the colour gate, which only
 * ever REMOVES clearly non-paintable pixels (charcoal railings, dark door
 * leaves, window glass and grills) so paint never lands on fixtures — the
 * mask boundaries otherwise stay exactly where the model drew them.
 * Persisted on the project as a comma-separated list of names so the async
 * worker sees the same choice; an EMPTY stored string means the admin
 * explicitly chose no steps (fully raw masks).
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

    /** Steps applied when the project has no stored choice (no admin ever
     *  touched the testing panel): the fixture-protecting colour gate only. */
    public static Set<MaskEnhancement> defaultSet() {
        return EnumSet.of(COLOUR_GATE);
    }

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

    /** Joins a set into the stored CSV form. An empty set becomes the empty
     *  string — a stored, explicit "no steps" — NOT null, which would read
     *  back as "never chosen" and re-activate {@link #defaultSet()}. */
    public static String toCsv(Set<MaskEnhancement> set) {
        if (set == null || set.isEmpty()) return "";
        return set.stream().map(Enum::name).sorted().collect(Collectors.joining(","));
    }
}
