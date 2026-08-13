package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Razorpay Checkout success payload for an AI credit purchase, sent back for verification. */
@Data
public class VerifyAiCreditPurchaseRequest {

    @NotBlank
    private String orderId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;
}
