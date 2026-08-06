package com.gridstore.huevista.billing.model;

/**
 * Where a checkout attempt stopped.
 *
 * <p>The order below is the order things happen in, and the interesting states are the
 * ones that are NOT {@link #PAID}: each names a different culprit, which is the whole
 * point of recording them separately rather than as one "didn't pay" bucket.
 */
public enum PaymentAttemptStatus {

    /**
     * We created the order/subscription at Razorpay and handed it to the browser.
     * Stuck here means the buyer never even got a Checkout window — a script that
     * failed to load, a tab closed on the way, an ad blocker.
     */
    CREATED("Order created", false),

    /** Checkout actually opened in front of the buyer. */
    OPENED("Checkout opened", false),

    /**
     * The buyer closed Checkout without paying. THE abandonment signal, and the
     * reason this table exists: no money moved, so no other record in the system
     * would ever mention it.
     */
    ABANDONED("Abandoned", true),

    /**
     * Razorpay refused the payment — declined card, failed UPI collect, expired
     * session. {@code errorCode}/{@code errorDescription} carry the gateway's reason.
     */
    FAILED("Payment failed", true),

    /**
     * The card was charged but our verification did not complete — bad signature, or
     * our own endpoint erroring out. The dangerous one: the buyer has paid and may
     * have nothing to show for it, so these should be zero and any row here is an
     * incident.
     */
    VERIFY_FAILED("Verification failed", true),

    /** Verified, entitlement granted. The only happy ending. */
    PAID("Paid", true);

    private final String displayName;
    private final boolean terminal;

    PaymentAttemptStatus(String displayName, boolean terminal) {
        this.displayName = displayName;
        this.terminal = terminal;
    }

    public String getDisplayName() {
        return displayName;
    }

    /** True once the attempt has finished, one way or another. */
    public boolean isTerminal() {
        return terminal;
    }

    /** Money left the buyer's account but the purchase did not complete. */
    public boolean isMoneyAtRisk() {
        return this == VERIFY_FAILED;
    }
}
