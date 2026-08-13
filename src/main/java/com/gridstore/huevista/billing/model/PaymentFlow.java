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

    /** Another validity window on a lapsed project, or a closed one opened back up. */
    REOPEN("Project reopen"),

    /** One more AI render on a project that already spent its included one. */
    RENDER("Extra AI image"),

    /** A top-up of the AI image wallet — the rail a customer uses, and the one a shop
     *  uses to hold images in advance rather than paying per project. */
    AI_CREDITS("AI image credits"),

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
