package com.gridstore.huevista.billing.dto;

import jakarta.validation.constraints.NotBlank;
import lombok.Data;

/**
 * Razorpay Checkout success payload for a subscription authorization, sent back to
 * the server so it can verify the HMAC signature and activate the plan synchronously
 * (rather than waiting on the {@code subscription.activated} webhook).
 */
@Data
public class VerifySubscriptionRequest {

    @NotBlank
    private String subscriptionId;

    @NotBlank
    private String paymentId;

    @NotBlank
    private String signature;
}
