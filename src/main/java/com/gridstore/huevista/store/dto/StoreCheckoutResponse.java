package com.gridstore.huevista.store.dto;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.gridstore.huevista.auth.dto.AuthResponse;
import lombok.Builder;
import lombok.Data;

import java.time.Instant;

/**
 * Returned when a kiosk payment verifies: the code the customer keeps, and a session on
 * the account their purchase now lives on.
 *
 * <p>The session is what lets a walk-in start work at the counter instead of being sent
 * to a sign-up form with wet paint samples in their hand. It is an ordinary account
 * session — the account is real, just unclaimed — so the studio, the entitlement and the
 * quota all behave exactly as they do for anyone else.
 *
 * <p>The code is still here and still matters, but it is the SHOP's reference: the eight
 * characters the counter reads to mix what the customer chose. It is not how the customer
 * gets back in. That is {@link #accountEmail} — see {@code KioskReentryService} for why a
 * receipt that never expires makes a poor password.
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

    /**
     * A live session on the account the purchase landed on, so the studio opens straight
     * away. The client stores this exactly as it stores any other sign-in.
     */
    private AuthResponse session;

    /**
     * The address the receipt went to, echoed back so the kiosk can show the customer
     * where to look — and so a typo is visible while they are still standing there.
     * Null when they gave none, which is the case where the browser session is all they
     * have and the screen says so.
     */
    private String accountEmail;

    /**
     * True when the purchase attached to an account the customer ALREADY had, rather
     * than opening a new one. The kiosk uses it to drop the "add this to your account"
     * offer — there is nothing to merge, and offering it would be nonsense.
     */
    private boolean existingAccount;
}
