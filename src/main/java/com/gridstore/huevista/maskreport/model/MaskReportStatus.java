package com.gridstore.huevista.maskreport.model;

/** Where a report is in the admin's queue. */
public enum MaskReportStatus {

    /** Just came in — nobody has looked. */
    NEW,

    /** An admin has picked it up. */
    IN_REVIEW,

    /** Dealt with; drops out of the default queue view. */
    RESOLVED
}
