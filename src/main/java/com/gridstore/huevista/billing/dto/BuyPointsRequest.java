package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * How many points to buy. Only the COUNT is client-supplied — the amount charged is
 * derived from it server-side, so nobody can name their own price.
 */
@Data
public class BuyPointsRequest {

    @NotNull
    @Min(value = 1, message = "Say how many points to buy")
    private Integer points;
}
