package com.gridstore.huevista.project.model;

/**
 * Paintable-surface category assigned by auto-segmentation. Drives how the
 * frontend groups regions and which default colors get applied (main color,
 * accent/highlighter color, trim color).
 */
public enum RegionCategory {
    /** Largest paintable wall — typically behind the main furniture. */
    MAIN_WALL,
    /** Second-largest wall — used as the highlighter/accent. */
    ACCENT_WALL,
    /** Any additional walls beyond main + accent. */
    OTHER_WALL,
    /** Window frames, door frames, baseboards, crown molding (the "border"). */
    TRIM,
    /** Overhead interior ceiling surface — only detected for indoor scenes. */
    CEILING,
    /** Door leaves/panels themselves (not their frame, which is TRIM). */
    DOOR,
    /** Window openings — glass panes and sashes (not their frame, which is TRIM). */
    WINDOW,
    /** User-created via click-to-segment. Category unknown until user labels it. */
    MANUAL
}
