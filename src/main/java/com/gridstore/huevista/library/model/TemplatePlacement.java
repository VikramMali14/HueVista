package com.gridstore.huevista.library.model;

import java.util.Locale;

/**
 * Which public page a published room appears on.
 *
 * The library used to feed exactly one surface — the gallery — and "published"
 * meant "on the gallery". It now feeds two, and they are read by different
 * people for different reasons: the gallery is a grid to browse and paint from,
 * "Our work" is a portfolio with a story attached to each room. Some rooms suit
 * both, most suit one, and which one is an editorial call the admin makes per
 * room rather than something derivable from the room itself.
 *
 * Kept as one column rather than two booleans because the admin is choosing
 * between three named outcomes, not ticking two independent flags — and a
 * two-boolean shape admits a fourth state (neither) that means "published to
 * nowhere", which is what {@code published = false} already says.
 */
public enum TemplatePlacement {

    /** The /gallery grid only. What every room published before this existed does. */
    GALLERY,

    /** The /work portfolio only. The default for anything published from now on. */
    WORK,

    /** Both pages. */
    BOTH;

    /** The default for a room published without saying where it goes. */
    public static final TemplatePlacement DEFAULT = WORK;

    public boolean onGallery() {
        return this == GALLERY || this == BOTH;
    }

    public boolean onWork() {
        return this == WORK || this == BOTH;
    }

    /** Whether this room belongs on the given surface. */
    public boolean shows(TemplatePlacement surface) {
        return switch (surface) {
            case GALLERY -> onGallery();
            case WORK -> onWork();
            case BOTH -> true;
        };
    }

    /**
     * Parse a request or query value, falling back to {@code fallback} when it is
     * absent and rejecting anything else.
     *
     * A misspelled placement is refused rather than silently defaulted: quietly
     * turning "our-work" into GALLERY would put a room on the wrong page and leave
     * the admin looking for it on the right one.
     */
    public static TemplatePlacement parse(String raw, TemplatePlacement fallback) {
        if (raw == null || raw.isBlank()) return fallback;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException(
                    "Unknown placement: " + raw + ". Use GALLERY, WORK or BOTH.");
        }
    }
}
