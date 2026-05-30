package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/** Razorpay Checkout success payload, sent back to the server for verification. */
@Data
public class VerifyProjectCreditRequest {

    @NotBlank
    private String orderId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;
}
