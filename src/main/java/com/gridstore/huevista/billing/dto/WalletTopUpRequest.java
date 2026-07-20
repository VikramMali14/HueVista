package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.Min;
import lombok.Data;

/** Amount the retailer wants to add to their prepaid billing wallet. Bounds are
 *  enforced server-side against the configured min/max on top of this floor. */
@Data
public class WalletTopUpRequest {

    @Min(value = 1, message = "Amount must be positive")
    private long amountPaise;
}
