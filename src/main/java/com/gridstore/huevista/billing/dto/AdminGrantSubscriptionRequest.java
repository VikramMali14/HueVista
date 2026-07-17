package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.Plan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/** Admin grant: activate a subscription for a user without a payment. */
@Data
public class AdminGrantSubscriptionRequest {

    @NotNull(message = "Plan is required")
    private Plan plan;

    /** Validity window in days from now. */
    @Min(value = 1, message = "Days must be at least 1")
    @Max(value = 3650, message = "Days cannot exceed 3650")
    private int days = 30;

    /** Optional override of the plan's monthly AI generation limit. */
    @Min(value = 1, message = "AI generation limit must be at least 1")
    private Integer aiGenerationsLimit;
}
