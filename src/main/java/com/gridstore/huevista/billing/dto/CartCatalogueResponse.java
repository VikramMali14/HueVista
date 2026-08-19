package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

/**
 * The customer's shop counter: what is for sale, what it costs, what an offer would take
 * off, and what the buyer already holds.
 *
 * <p>Everything the cart screen needs, in one call. The alternative — a price endpoint, a
 * balances endpoint and an offers endpoint — would let the three disagree with each other
 * for a page load, and the one place that must never happen is a screen with a Pay button
 * on it.
 *
 * <p>The prices here are FINAL: what is quoted is what Razorpay is asked for. The client
 * multiplies them by its quantities to draw a running total, and the server prices the
 * order again from the same numbers when it is opened — the client's arithmetic is a
 * courtesy to the buyer, never the authority.
 */
@Data
@Builder
public class CartCatalogueResponse {

    /**
     * Whether this account may buy from the catalogue at all.
     *
     * <p>False for a shop, which buys plans, points and projects at its tier's rate, and
     * for a painter or distributor, who own no projects. The cart hides itself rather than
     * showing a counter whose every button comes back 403.
     */
    private boolean eligible;

    /** One project, on its own. */
    private int projectPricePaise;

    /** One AI image credit, on its own. */
    private int creditPricePaise;

    /** The combo, and what is in it. */
    private int comboPricePaise;
    private int comboProjects;
    private int comboCredits;

    /**
     * The special offer: three rooms and three pictures for the price of two of each.
     *
     * <p>{@link #bundleAvailable} is the switch the screen reads. False takes the line off
     * the counter entirely — a bundle configured down to no discount is hidden rather than
     * shown as a saving of nothing.
     *
     * <p>{@link #bundleListPricePaise} is what the same contents cost bought line by line.
     * It travels so the strike-through beside the price is the server's own arithmetic
     * rather than the client's: a screen that multiplies the parts itself will one day
     * multiply them differently.
     */
    private boolean bundleAvailable;
    private int bundlePricePaise;
    private int bundleListPricePaise;
    private int bundleProjects;
    private int bundleCredits;

    /** Days everything on this counter is good for — a year, on every line. */
    private int validDays;

    /** The most of any one line a single order may hold. */
    private int maxQuantity;

    /** The offers, weakest first, so the cart can show the next one to reach for. */
    private List<Offer> offers;

    /** Projects already paid for and not yet started. */
    private int availableProjects;

    /** Spendable AI image credits. */
    private int creditBalance;

    /** When the soonest batch of credits lapses, and how many go with it. Null and 0 when
     *  nothing the account holds carries a date. */
    private LocalDateTime creditsExpireAt;
    private int creditsExpiring;

    private String currency;

    /** One offer on the board. */
    @Data
    @Builder
    public static class Offer {
        /** What the buyer types or taps — "HUE10". */
        private String code;
        /** The subtotal that unlocks it, in paise. Inclusive: exactly this much qualifies. */
        private int minSubtotalPaise;
        private int percentOff;
    }
}
