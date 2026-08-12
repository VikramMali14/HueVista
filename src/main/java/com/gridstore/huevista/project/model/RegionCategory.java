package com.gridstore.huevista.project.model;

/**
 * Paintable-surface category assigned by auto-segmentation. Drives how the
 * frontend groups regions and which default colors get applied (main color,
 * accent/highlighter color, trim color).
 *
 * <p>Each category owns the NAME it is shown under, because three places used to
 * spell these three surfaces three different ways: the studio offered "Main wall /
 * Accent wall / Border" before a photo went up, auto-segmentation wrote "Main Wall /
 * Accent Wall / Trim &amp; Frames" afterwards, and {@code ProjectService} had a
 * third variant again for regions it created itself. A customer picking colours
 * watched the surfaces rename themselves halfway through the job.
 */
public enum RegionCategory {
    /** Largest paintable wall — typically behind the main furniture. */
    MAIN_WALL("Main wall"),
    /** Second-largest wall — used as the highlighter/accent. */
    ACCENT_WALL("Accent wall"),
    /** Any additional walls beyond main + accent. */
    OTHER_WALL("Wall"),
    /** Window frames, door frames, baseboards, crown molding (the "border"). */
    TRIM("Trim & frames"),
    /** User-created via click-to-segment. Category unknown until user labels it. */
    MANUAL("Region");

    private final String defaultLabel;

    RegionCategory(String defaultLabel) {
        this.defaultLabel = defaultLabel;
    }

    /**
     * What this surface is called on screen. MANUAL's is a stem — a hand-drawn
     * region is numbered ("Region 2"), because there can be any number of them.
     */
    public String getDefaultLabel() {
        return defaultLabel;
    }
}
