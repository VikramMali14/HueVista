package com.gridstore.huevista.project.model;

public enum ProjectStatus {
    CREATED,    // just created, no segmentation yet
    SEGMENTING, // SAM 2 job in flight
    SEGMENTED,  // masks ready, user can apply colors
    FAILED      // segmentation job failed
}
