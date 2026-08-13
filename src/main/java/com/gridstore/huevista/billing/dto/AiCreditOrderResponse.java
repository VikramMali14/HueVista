package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/** Returned to the client to open Razorpay Checkout for an AI image credit top-up. */
@Data
@Builder
public class AiCreditOrderResponse {
    private String orderId;
    /** Credits this order buys. */
    private int credits;
    /** What it costs, in paise — derived server-side from the count at today's price. */
    private int amount;
    /** What the same order would have cost at the undiscounted list price, in paise.
     *  Equal to {@code amount} once the launch offer ends. */
    private int listAmount;
    /** The launch discount applied, as a whole percentage. 0 once the offer ends. */
    private int discountPercent;
    private String currency;
    private String razorpayKeyId;
}
