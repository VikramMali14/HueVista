package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.Plan;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSubscriptionRequest {

    @NotNull(message = "Plan is required")
    private Plan plan;

    // Client-supplied multiplier on the billed amount — bound it server-side so a
    // tampered request can't create a wildly mispriced subscription.
    @Min(value = 1, message = "Quantity must be at least 1")
    @Max(value = 100, message = "Quantity must be at most 100")
    private int quantity = 1;
}
