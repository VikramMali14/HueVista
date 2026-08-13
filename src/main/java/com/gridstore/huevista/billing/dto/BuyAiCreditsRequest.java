package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;
import lombok.Data;

/**
 * How many AI image credits to buy. Only the COUNT is client-supplied — the amount charged
 * is derived from it server-side at the current price and discount, so nobody can name
 * their own price or claim a launch rate that has ended.
 */
@Data
public class BuyAiCreditsRequest {

    @NotNull
    @Min(value = 1, message = "Say how many AI image credits to buy")
    private Integer credits;
}
