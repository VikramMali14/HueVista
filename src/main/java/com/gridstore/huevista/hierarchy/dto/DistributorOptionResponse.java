package com.gridstore.huevista.hierarchy.dto;

import lombok.Builder;
import lombok.Data;

/**
 * One choice in the admin's "which distributor does this shop belong under?"
 * dropdown. Carries enough to tell two distributors with similar names apart —
 * the owner, the city, and how many shops they already carry.
 */
@Data
@Builder
public class DistributorOptionResponse {

    private String orgId;
    private String name;
    private String city;
    private String state;
    private String ownerName;
    private String ownerEmail;

    /** Shops already filed under this distributor. */
    private long shopCount;

    /** True for the platform's own distributor — the default, shown first and labelled. */
    private boolean house;
}
