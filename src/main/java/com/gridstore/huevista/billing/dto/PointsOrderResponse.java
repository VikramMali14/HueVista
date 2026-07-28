package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/** Returned to the client to open Razorpay Checkout for a points purchase. */
@Data
@Builder
public class PointsOrderResponse {
    private String orderId;
    /** Points this order buys. */
    private int points;
    /** What it costs, in paise — derived server-side from the count. */
    private int amount;
    private String currency;
    private String razorpayKeyId;
}
