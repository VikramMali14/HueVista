package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/** Returned to the client to open Razorpay Checkout for a one-time project purchase. */
@Data
@Builder
public class ProjectCreditOrderResponse {
    private String orderId;
    private int amount;          // in paise
    private String currency;     // e.g. INR
    private String razorpayKeyId;
}
