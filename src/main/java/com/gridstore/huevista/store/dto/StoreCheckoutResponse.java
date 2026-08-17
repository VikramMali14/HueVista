package com.gridstore.huevista.store.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Returned when a kiosk payment verifies: the code the customer keeps.
 *
 * <p>There is no session in here. A code is redeemed onto a customer ACCOUNT, so the
 * buyer signs in (or registers) and redeems this code to claim what they paid for. The
 * code is shown on the receipt and mailed to them, so a payment can never end up
 * attached to nothing — which is exactly what the old anonymous guest token risked
 * whenever the browser that made the purchase was lost.
 */
@Data
@Builder
public class StoreCheckoutResponse {
    private String code;
    private String shopName;
    /** Days left to redeem this code onto an account. */
    private int validDays;
    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private Instant expiresAt;
    /** What was paid, for the on-screen receipt. */
    private int amountPaise;
}
