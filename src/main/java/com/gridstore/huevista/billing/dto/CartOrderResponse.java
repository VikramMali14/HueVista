package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/**
 * The Razorpay order for a basket, with the bill that produced it.
 *
 * <p>The breakdown travels back rather than only the amount, so the sheet the buyer sees
 * and the payment they are about to make are the same arithmetic. A cart that showed its
 * own total and then opened Checkout for a different one would be right about the money and
 * wrong about the only thing the buyer can check.
 */
@Data
@Builder
public class CartOrderResponse {

    private String orderId;

    /** What was rung up, before the offer. */
    private int subtotalPaise;

    /** The offer that applied — its code, its rate, and what it took off. Blank and 0
     *  when the basket earned none. */
    private String discountCode;
    private int discountPercent;
    private int discountPaise;

    /** What Razorpay is being asked for. */
    private int amountPaise;

    /** What this order will hand over once it is paid for: combos already unpacked into
     *  the projects and credits they contain. */
    private int projectsGranted;
    private int creditsGranted;

    /** How long both are good for. */
    private int validDays;

    private String currency;
    private String razorpayKeyId;
}
