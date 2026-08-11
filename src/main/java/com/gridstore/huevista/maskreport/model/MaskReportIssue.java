package com.gridstore.huevista.maskreport.model;

/**
 * What the person reporting says went wrong with the AI run.
 *
 * These are the two halves of the pipeline the user can actually SEE — the photo
 * clean-up and the wall detection — plus an escape hatch. Deliberately coarse:
 * the point is to tell the admin which stage to look at first, and the free-text
 * note carries everything else.
 */
public enum MaskReportIssue {

    /** Walls were missed, merged, spilled onto furniture, or nothing was detected. */
    MASK_NOT_GENERATED_PROPERLY,

    /** The clean-up stage damaged the photo — smeared furniture, warped edges. */
    IMAGE_NOT_CLEANED_PROPERLY,

    /** Something else — read the note. */
    OTHER
}
