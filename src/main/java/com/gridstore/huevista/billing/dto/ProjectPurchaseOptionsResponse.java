package com.gridstore.huevista.billing.dto;

import lombok.Builder;
import lombok.Data;

/**
 * What one extra project costs THIS account, on both rails, and what it buys.
 *
 * The price moves with the buyer's plan — 80 points / ₹199 with no plan, down to
 * 40 points / ₹45 on Business — so it is quoted per account rather than stated as a
 * constant anywhere. Points are the cheaper rail on every tier; that gap is the whole
 * reason a shop bothers to top up or run a kiosk.
 */
@Data
@Builder
public class ProjectPurchaseOptionsResponse {

    /** Whether a paid plan is currently covering this account — what sets the rate below. */
    private boolean subscribed;

    /** The plan the price was read off ("FREE" when no paid plan is covering the account). */
    private String pricingPlan;

    /** What one project costs, in points. */
    private int projectPricePoints;

    /** What one project costs in money, in paise, GST included. */
    private int projectPricePaise;

    /**
     * The bundle: how many projects it grants, and what it costs in paise.
     *
     * Quoted alongside the single price rather than instead of it, because the saving is
     * only legible next to the thing it discounts — "3 for ₹398" means nothing without
     * "₹199 each" beside it. Money-only: the points rail already discounts by tier.
     */
    private int bundleCredits;
    private int bundlePricePaise;

    /** What another window on a lapsed project costs — points, and money in paise.
     *  Flat on both rails: a reopen buys more time on work already paid for once, so
     *  unlike a new project it does not get cheaper with the tier. */
    private int reopenPricePoints;
    private int reopenPricePaise;

    /** The account's spendable balance, so the caller can say whether it is enough. */
    private int pointsBalance;

    /**
     * Whether this account can spend points at ALL — not whether it happens to hold enough.
     *
     * Points are a shop currency: RewardPointsService refuses every non-retailer, so a
     * CUSTOMER reads false here however the balance looks. Without it a client has only the
     * price and the balance to go on, and the honest reading of "80 points, 0 held" is
     * "top up" — which is why a self-signed-up customer was being offered a points button
     * that could only ever come back 403. False means: money is the only rail, don't offer
     * the other one.
     */
    private boolean pointsEligible;

    /** Days of access a purchase (or a reopen) opens. */
    private int validDays;

    /**
     * Projects already paid for and not yet created — the standalone ledger credits a
     * shop between plans holds. A shop WITH a live plan gets its extras added to that
     * plan's allowance instead, where they read as {@code purchasedProjectCredits}.
     */
    private int availableCredits;
}
