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

    /** Extra AI image generations to ADD to the current limit. */
    @Min(value = 1, message = "Added generations must be at least 1")
    @Max(value = 1_000_000, message = "Added generations cannot exceed 1000000")
    private Integer addAiGenerations;

    /** Days to extend the current period end by (from now if already lapsed). */
    @Min(value = 1, message = "Extension must be at least 1 day")
    @Max(value = 3650, message = "Extension cannot exceed 3650 days")
    private Integer extendDays;
}
