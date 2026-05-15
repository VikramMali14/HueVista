package com.gridstore.huevista.billing.dto;

import com.gridstore.huevista.billing.model.Plan;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

@Data
public class CreateSubscriptionRequest {

    @NotNull(message = "Plan is required")
    private Plan plan;

    private int quantity = 1;
}
