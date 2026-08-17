package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Razorpay Checkout success payload for a basket, sent back for verification. */
@Data
public class VerifyCartPurchaseRequest {

    @NotBlank
    private String orderId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;
}
