package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Razorpay Checkout success payload for a points purchase, sent back for verification. */
@Data
public class VerifyPointsPurchaseRequest {

    @NotBlank
    private String orderId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;
}
