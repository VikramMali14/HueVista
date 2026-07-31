package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Razorpay Checkout success payload for a single-project purchase, sent back for verification. */
@Data
public class VerifyProjectPurchaseRequest {

    @NotBlank
    private String orderId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;
}
