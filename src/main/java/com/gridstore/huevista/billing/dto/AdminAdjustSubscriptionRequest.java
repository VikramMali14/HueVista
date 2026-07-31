package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * Admin adjustment of a user's existing subscription. At least one field must be
 * set; both may be combined in one call. Extending a lapsed subscription
 * reactivates it (status back to ACTIVE).
 */
@Data
public class AdminAdjustSubscriptionRequest {

    /**
     * Extra projects to grant. They land in the subscription's purchased-credit bucket,
     * which survives a renewal — writing them into the monthly limit instead meant the
     * grant quietly evaporated the next time the plan renewed and rebuilt that limit.
     */
    @Min(value = 1, message = "Added projects must be at least 1")
    @Max(value = 1_000_000, message = "Added projects cannot exceed 1000000")
    private Integer addProjects;

    /** Days to extend the current period end by (from now if already lapsed). */
    @Min(value = 1, message = "Extension must be at least 1 day")
    @Max(value = 3650, message = "Extension cannot exceed 3650 days")
    private Integer extendDays;
}
