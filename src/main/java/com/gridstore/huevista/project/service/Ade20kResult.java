package com.gridstore.huevista.project.service;

import java.util.List;

/**
 * Result of ADE20K semantic segmentation: per-class binary masks identified
 * by the model. Each entry maps an ADE20K class ID to a PNG mask covering
 * the pixels predicted to belong to that class.
 *
 * Key ADE20K classes for paint visualization:
 *   0:  wall              — MAIN paintable surface
 *   1:  building          — exterior facade wall
 *   2:  sky               — must exclude
 *   3:  floor             — must exclude (interior ground)
 *   4:  tree              — must exclude
 *   5:  ceiling           — interior trim/ceiling
 *   6:  road              — must exclude
 *   8:  windowpane        — exclude (do not paint glass)
 *   9:  grass             — must exclude
 *   13: earth/ground      — must exclude
 *   14: door              — exclude (door frame may be paintable but slab isn't)
 *   17: plant             — must exclude
 *   22: painting          — exclude (decoration on wall)
 *   26: mirror            — exclude
 *   32: fence             — must exclude
 *   41: signboard         — must exclude
 *   43: railing           — TRIM candidate
 *   70: car               — must exclude
 *   80: light             — exclude (light fixture)
 *   87: television        — exclude
 *   132: glass            — exclude
 *
 * A {@code null} or empty {@code perClassMasks} indicates the model
 * returned no usable output — the caller should fall back to a
 * different segmentation method.
 */
public record Ade20kResult(
        java.util.Map<Integer, byte[]> perClassMasks,
        int imageWidth,
        int imageHeight
) {

    /** ADE20K class ID for "wall" — the primary paintable surface indoor. */
    public static final int CLASS_WALL = 0;

    /** ADE20K class ID for "building" — exterior facade wall. */
    public static final int CLASS_BUILDING = 1;

    /** ADE20K class ID for "ceiling". */
    public static final int CLASS_CEILING = 5;

    /** ADE20K class ID for "railing" — typically goes in TRIM. */
    public static final int CLASS_RAILING = 43;

    /**
     * ADE20K class IDs we want to EXCLUDE from any paintable mask. Covers
     * sky, ground, vegetation, openings, fixtures, decor, vehicles. We
     * never paint over these.
     */
    public static final List<Integer> EXCLUDE_CLASSES = List.of(
            2,    // sky
            3,    // floor
            4,    // tree
            6,    // road
            8,    // windowpane
            9,    // grass
            13,   // earth/ground
            14,   // door
            17,   // plant
            22,   // painting
            26,   // mirror
            32,   // fence
            41,   // signboard
            70,   // car
            80,   // light fixture
            87,   // television
            132   // glass
    );

    public boolean hasWall() {
        return perClassMasks != null
                && (perClassMasks.containsKey(CLASS_WALL) || perClassMasks.containsKey(CLASS_BUILDING));
    }
}
