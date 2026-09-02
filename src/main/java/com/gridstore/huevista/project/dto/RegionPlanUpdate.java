package com.gridstore.huevista.project.dto;

import com.gridstore.huevista.project.model.RegionCategory;
import lombok.Data;

/**
 * One line of the customer's paint plan: what a surface is, and whether it is being
 * painted at all.
 *
 * PATCH semantics per field — a null is "leave this alone", not "clear it". The studio
 * sends the whole plan when the plan panel is closed, but a user who only re-labelled one
 * wall must not have that write also reset the two fields they never touched.
 *
 * Deliberately separate from {@link RegionColorUpdate}, which is the every-two-seconds
 * autosave of whatever colour is on a wall. These are decisions rather than strokes: they
 * are made once, on purpose, and there are only ever a handful of them.
 */
@Data
public class RegionPlanUpdate {

    /** Which region. A id belonging to another project simply matches nothing. */
    private Long regionId;

    /**
     * The role this surface plays in the scheme — main wall, accent wall, another wall,
     * trim. Null leaves it as detection (or the Mask Studio) filed it.
     *
     * This is the field that decides which colour of a suggested combination lands here,
     * so it is a user-facing choice and not just a label: the whole reason somebody opens
     * the plan panel is usually to say "no, THAT one is the accent".
     */
    private RegionCategory category;

    /** What the wall is called on screen and on the colour board. Blank leaves it. */
    private String label;

    /**
     * Whether the wall is in the scheme being painted. Null leaves it as it was.
     *
     * False keeps the region and its mask, and takes it out of the palettes, out of
     * "Apply all" and off the board — see the column comment on {@code regions.in_plan}.
     */
    private Boolean inPlan;
}
