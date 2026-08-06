package com.gridstore.huevista.billing.model;

/**
 * Which checkout a {@link PaymentAttempt} belongs to.
 *
 * <p>Every one of these opens a Razorpay Checkout, and every one of them can be
 * abandoned. Keeping them in one enum (rather than one table per flow) is what lets
 * the admin report answer "how much money walked away this week" across the whole
 * product instead of per feature.
 */
public enum PaymentFlow {

    /** Monthly plan — Razorpay *subscription*, so the reference is a sub_… id. */
    SUBSCRIPTION("Plan subscription"),

    /** Reward-points top-up. */
    POINTS("Points top-up"),

    /** One extra project at the buyer's plan rate. */
    PROJECT("Extra project"),

    /** Another validity window on a lapsed project. */
    REOPEN("Project reopen"),

    /** In-store kiosk — the buyer is a walk-in customer, not a signed-in user. */
    STORE_KIOSK("Store kiosk");

    private final String displayName;

    PaymentFlow(String displayName) {
        this.displayName = displayName;
    }

    public String getDisplayName() {
        return displayName;
    }
}
