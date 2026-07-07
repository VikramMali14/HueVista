package com.gridstore.huevista.store.dto;

import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

@Data
public class RequestRedemptionRequest {

    @NotNull
    @Min(value = 1, message = "Amount is required")
    private Integer amountPaise;

    /** VPA shape: handle@bank, e.g. shopname@okhdfcbank. */
    @NotBlank
    @Pattern(regexp = "^[A-Za-z0-9._-]{2,256}@[A-Za-z]{2,64}$",
            message = "Enter a valid UPI id, e.g. shopname@okhdfcbank")
    private String upiId;
}
