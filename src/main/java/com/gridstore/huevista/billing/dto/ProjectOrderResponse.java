package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/** Returned to the client to open Razorpay Checkout for one extra project. */
@Data
@Builder
public class ProjectOrderResponse {
    private String orderId;
    /** The tier this was priced at — "FREE" when no paid plan is covering the account. */
    private String pricingPlan;
    /** What it costs, in paise — derived server-side from the buyer's plan. */
    private int amount;
    private String currency;
    private String razorpayKeyId;
}
