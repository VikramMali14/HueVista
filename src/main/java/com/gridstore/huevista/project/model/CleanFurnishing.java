package com.gridstore.huevista.project.model;

import java.util.Locale;

/**
 * What the photo clean-up does with the furniture already in the room.
 *
 * <p>Not the same knob as {@code ProjectRender.Furnishing}, and deliberately narrower.
 * That one dresses a finished photograph and may ADD things ({@code STAGED}); this one
 * decides what the working canvas looks like — the image masks are aligned to and the
 * customer colours in — so it may only ever take things away. There is no STAGED here,
 * because a canvas containing a sofa the room does not have is a canvas the customer
 * cannot check their own house against.
 */
public enum CleanFurnishing {

    /**
     * Everything stays exactly where the camera found it. The default, and the behaviour
     * every run had before this existed — the clean prompt is untouched on this path.
     */
    KEEP,

    /**
     * Clear the loose furniture so more wall is visible and paintable.
     *
     * <p>Fixed elements stay: built-in cabinetry, wardrobes, kitchen units, radiators,
     * switchboards, fittings. This is emptying a room, not refurbishing one.
     */
    EMPTY;

    /** The value a run gets when nobody chose one. */
    public static final CleanFurnishing DEFAULT = KEEP;

    /**
     * @throws IllegalArgumentException on anything that isn't a member — a typo'd knob
     *         must not quietly run the default, or a prompt experiment reports a result
     *         for a setting it never actually used. Null and blank mean "not asked".
     */
    public static CleanFurnishing parse(String raw) {
        if (raw == null || raw.isBlank()) return DEFAULT;
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("cleanFurnishing must be KEEP or EMPTY.");
        }
    }
}
